package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxphys.api.event.FoxPhysEvents;
import dev.foxphys.api.event.SoftBodyRemovedEvent;
import dev.foxphys.api.event.SoftBodySpawnedEvent;
import dev.foxphys.net.FoxPhysNetwork;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import dev.foxphys.Protocol.BodyState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Java side of the soft bodies (XPBD in Rust). Rust owns all state; Java sends JSON requests,
 * steps the registry once per server tick (1/20 s, 8 substeps) while bodies exist, samples nearby
 * Minecraft collision shapes and rigid AABBs, queues reaction impulses, and reports results.
 */
public final class SoftBodies {
    private SoftBodies() {}

    static final double TICK_DT = 0.05;
    static final int SUBSTEPS = 8;
    /** Bodies known to Rust (updated from responses). */
    private static volatile int count = 0;
    private static volatile boolean broken = false;
    /** Consecutive ticks in which the view (sending shapes to clients) failed. Reset on success; never disables the view. */
    private static int viewFailures = 0;
    /** True after at least one non-empty shape update, so an empty update can clear the clients. */
    private static boolean published = false;
    /** Ticks since the surface triangles were last sent in full (see publish). */
    private static int ticksSinceFullTris = 0;
    /** Bodies whose surface reached the clients in the last full send. */
    private static Set<Integer> trisSent = Set.of();
    static final int TRIS_EVERY_TICKS = 20;
    /** True after the native library refused the wind op (no wind for soft bodies). */
    private static volatile boolean windUnsupported = false;
    private static final double SAMPLE_RADIUS = 1.0;
    private static final double CELL = 0.05;
    private static final int MAX_EXTERNAL_BOXES = 512;
    private static final int MAX_COUPLED_BODIES = 64;
    /** The native soft world is global to the JVM; this map supplies the owning dimension and last bounds. */
    private static final Map<Integer, SoftMeta> META = new HashMap<>();

    private static final class SoftMeta {
        ServerWorld world;
        double[] min;
        double[] max;

        SoftMeta(ServerWorld world, double x, double y, double z, String preset) {
            this.world = world;
            double[] size = switch (preset) {
                case "rubber" -> new double[] {4 * CELL, 4 * CELL, 4 * CELL};
                case "jelly" -> new double[] {6 * CELL, 6 * CELL, 6 * CELL};
                case "cloth" -> new double[] {16 * CELL, CELL, 16 * CELL};
                case "rope" -> new double[] {24 * CELL, CELL, CELL};
                default -> new double[] {CELL, CELL, CELL};
            };
            this.min = new double[] {x, y, z};
            this.max = new double[] {x + size[0], y + size[1], z + size[2]};
        }
    }

    /** One JSON round trip. Throws IllegalStateException with the Rust message on error. */
    public static JsonObject call(String op, JsonObject args) {
        NativeLoader.ensureLoaded();
        args.addProperty("op", op);
        String text = NativeBridge.softExchange(args.toString());
        JsonObject out = JsonParser.parseString(text).getAsJsonObject();
        if (out.has("bodies") && out.get("bodies").isJsonPrimitive()) {
            count = out.get("bodies").getAsInt();
        }
        return out;
    }

    /** Compatibility entry point without a world owner; such bodies have no Minecraft collider sampling. */
    public static JsonObject spawn(String preset, double x, double y, double z, double floorY) {
        return spawn(null, preset, x, y, z, floorY);
    }

    public static JsonObject spawn(ServerWorld world, String preset, double x, double y, double z, double floorY) {
        JsonObject a = new JsonObject();
        a.addProperty("preset", preset);
        JsonArray origin = new JsonArray();
        origin.add(x); origin.add(y); origin.add(z);
        a.add("origin", origin);
        a.addProperty("floor", floorY);
        JsonObject out = call("spawn", a);
        int id = out.get("id").getAsInt();
        META.put(id, new SoftMeta(world, x, y, z, preset));
        count++;
        FoxPhysEvents.SOFT_BODY_SPAWNED.invoker().accept(new SoftBodySpawnedEvent(id, preset));
        return out;
    }

    /** Binds a world-less API body to the dimension of a rigid-body attachment. */
    static void bindWorld(int id, ServerWorld world) {
        SoftMeta meta = META.get(id);
        if (meta == null) {
            JsonObject status = withId("status", id);
            JsonArray center = status.getAsJsonArray("center");
            meta = new SoftMeta(world, center.get(0).getAsDouble(), center.get(1).getAsDouble(), center.get(2).getAsDouble(), status.get("preset").getAsString());
            META.put(id, meta);
        } else if (meta.world == null) {
            meta.world = world;
        }
    }

    /** Removes one soft body and fires {@link SoftBodyRemovedEvent}. Throws IllegalStateException if Rust refuses. */
    public static void remove(int id) {
        SoftAttachments.forget(id);
        withId("remove", id);
        META.remove(id);
        count = Math.max(0, count - 1);
        FoxPhysEvents.SOFT_BODY_REMOVED.invoker().accept(new SoftBodyRemovedEvent(id));
    }

    public static JsonObject withId(String op, int id) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        return call(op, a);
    }

    /** Sets the air wind (m/s, world axes) for all soft bodies. Ignored while no body exists. */
    public static void setWind(Vec3d velocity) {
        if (count <= 0 || broken || windUnsupported) return;
        try {
            JsonObject a = new JsonObject();
            a.add("velocity", Protocol.vector(velocity.x, velocity.y, velocity.z));
            call("wind", a);
        } catch (RuntimeException e) {
            // Usually an old native library without the op: say so once instead of every tick.
            windUnsupported = true;
            FoxPhysMod.LOGGER.warn("FoxPhys: wind for soft bodies is off ({}). Update the native library.", e.getMessage());
        }
    }

    /** Advances all bodies by one server tick, sampling colliders in each body's owning dimension. */
    public static void tick(MinecraftServer server) {
        if (count <= 0 || broken) return;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("dt", TICK_DT);
            request.addProperty("substeps", SUBSTEPS);
            JsonArray environments = new JsonArray();
            for (Map.Entry<Integer, SoftMeta> entry : META.entrySet()) {
                SoftMeta meta = entry.getValue();
                if (meta.world == null || meta.world.getServer() != server) continue;
                JsonObject environment = new JsonObject();
                environment.addProperty("id", entry.getKey());
                environment.add("obstacles", nearbyBlockColliders(meta.world, meta.min, meta.max));
                environment.add("bodies", nearbyRigidBodies(meta.world, meta.min, meta.max));
                environments.add(environment);
            }
            request.add("environments", environments);
            JsonObject result = call("step", request);
            updateBounds(result.getAsJsonArray("bounds"));
            if (result.has("torn_constraints") && result.get("torn_constraints").getAsInt() > 0) {
                ticksSinceFullTris = TRIS_EVERY_TICKS;
            }
            queueCouplingImpulses(result.getAsJsonArray("body_impulses"));
        } catch (RuntimeException e) {
            broken = true;
            FoxPhysMod.LOGGER.error("FoxPhys soft bodies stopped after an error: {}", e.getMessage());
        }
    }

    private static JsonArray nearbyBlockColliders(ServerWorld world, double[] boundsMin, double[] boundsMax) {
        JsonArray out = new JsonArray();
        int minX = (int) Math.floor(boundsMin[0] - SAMPLE_RADIUS);
        int minY = (int) Math.floor(boundsMin[1] - SAMPLE_RADIUS);
        int minZ = (int) Math.floor(boundsMin[2] - SAMPLE_RADIUS);
        int maxX = (int) Math.floor(boundsMax[0] + SAMPLE_RADIUS);
        int maxY = (int) Math.floor(boundsMax[1] + SAMPLE_RADIUS);
        int maxZ = (int) Math.floor(boundsMax[2] + SAMPLE_RADIUS);
        scan:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.isOutOfHeightLimit(pos) || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(FoxPhysBlocks.PHYSICS_BLOCK)) continue;
                    for (Box box : state.getCollisionShape(world, pos).getBoundingBoxes()) {
                        if (out.size() >= MAX_EXTERNAL_BOXES) break scan;
                        double[] min = {x + box.minX, y + box.minY, z + box.minZ};
                        double[] max = {x + box.maxX, y + box.maxY, z + box.maxZ};
                        if (!nearBounds(min, max, boundsMin, boundsMax)) continue;
                        JsonObject obstacle = new JsonObject();
                        obstacle.add("min", Protocol.vector(min));
                        obstacle.add("max", Protocol.vector(max));
                        out.add(obstacle);
                    }
                }
            }
        }
        return out;
    }

    private record RigidCandidate(BodyState body, double[] min, double[] max, double distanceSquared) {}

    private static JsonArray nearbyRigidBodies(ServerWorld world, double[] boundsMin, double[] boundsMax) {
        JsonArray out = new JsonArray();
        Lab lab = FoxPhysMod.manager().labFor(world);
        if (lab == null || lab.failed() || lab.snapshot() == null || lab.snapshot().bodies() == null) return out;
        List<RigidCandidate> candidates = new ArrayList<>();
        double cx = (boundsMin[0] + boundsMax[0]) * 0.5;
        double cy = (boundsMin[1] + boundsMax[1]) * 0.5;
        double cz = (boundsMin[2] + boundsMax[2]) * 0.5;
        for (BodyState body : lab.snapshot().bodies()) {
            float[] min = body.aabbMin(), max = body.aabbMax(), velocity = body.velocity();
            if (!valid3(min) || !valid3(max) || !valid3(velocity) || !(Float.isFinite(body.mass()) && body.mass() > 0.0f)) continue;
            double[] worldMin = {lab.origin().getX() + min[0], lab.origin().getY() + min[1], lab.origin().getZ() + min[2]};
            double[] worldMax = {lab.origin().getX() + max[0], lab.origin().getY() + max[1], lab.origin().getZ() + max[2]};
            if (!nearBounds(worldMin, worldMax, boundsMin, boundsMax)) continue;
            double dx = (worldMin[0] + worldMax[0]) * 0.5 - cx;
            double dy = (worldMin[1] + worldMax[1]) * 0.5 - cy;
            double dz = (worldMin[2] + worldMax[2]) * 0.5 - cz;
            candidates.add(new RigidCandidate(body, worldMin, worldMax, dx * dx + dy * dy + dz * dz));
        }
        candidates.sort(Comparator.comparingDouble(RigidCandidate::distanceSquared));
        for (int i = 0; i < Math.min(MAX_COUPLED_BODIES, candidates.size()); i++) {
            RigidCandidate candidate = candidates.get(i);
            BodyState body = candidate.body();
            float[] velocity = body.velocity();
            JsonObject rigid = new JsonObject();
            rigid.addProperty("id", body.id());
            rigid.add("min", Protocol.vector(candidate.min()));
            rigid.add("max", Protocol.vector(candidate.max()));
            rigid.add("velocity", Protocol.vector(velocity[0], velocity[1], velocity[2]));
            rigid.addProperty("mass", body.mass());
            out.add(rigid);
        }
        return out;
    }

    private static boolean valid3(float[] vector) {
        return vector != null && vector.length >= 3
                && Float.isFinite(vector[0]) && Float.isFinite(vector[1]) && Float.isFinite(vector[2]);
    }

    private static boolean nearBounds(double[] min, double[] max, double[] bodyMin, double[] bodyMax) {
        for (int k = 0; k < 3; k++) {
            if (max[k] < bodyMin[k] - SAMPLE_RADIUS || min[k] > bodyMax[k] + SAMPLE_RADIUS) return false;
        }
        return true;
    }

    private static void updateBounds(JsonArray bounds) {
        if (bounds == null) return;
        for (JsonElement element : bounds) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            SoftMeta meta = META.get(row.get("id").getAsInt());
            if (meta == null) continue;
            double[] min = vector3(row.getAsJsonArray("min"));
            double[] max = vector3(row.getAsJsonArray("max"));
            if (min != null && max != null) {
                meta.min = min;
                meta.max = max;
            }
        }
    }

    private static double[] vector3(JsonArray array) {
        if (array == null || array.size() != 3) return null;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = array.get(i).getAsDouble();
            if (!Double.isFinite(out[i])) return null;
        }
        return out;
    }

    private static void queueCouplingImpulses(JsonArray impulses) {
        if (impulses == null || impulses.isEmpty()) return;
        Map<Lab, JsonArray> batches = new HashMap<>();
        for (JsonElement element : impulses) {
            if (!element.isJsonObject()) continue;
            JsonObject response = element.getAsJsonObject();
            SoftMeta meta = META.get(response.get("soft_id").getAsInt());
            if (meta == null || meta.world == null) continue;
            Lab lab = FoxPhysMod.manager().labFor(meta.world);
            if (lab == null || lab.failed()) continue;
            JsonArray impulse = response.getAsJsonArray("impulse");
            JsonArray point = response.getAsJsonArray("point");
            if (impulse == null || point == null || impulse.size() != 3 || point.size() != 3) continue;
            double ix = impulse.get(0).getAsDouble(), iy = impulse.get(1).getAsDouble(), iz = impulse.get(2).getAsDouble();
            double px = point.get(0).getAsDouble(), py = point.get(1).getAsDouble(), pz = point.get(2).getAsDouble();
            if (!Double.isFinite(ix) || !Double.isFinite(iy) || !Double.isFinite(iz)
                    || !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) continue;
            JsonObject command = Protocol.op("impulse");
            command.addProperty("id", response.get("id").getAsLong());
            command.add("impulse", Protocol.vector(ix, iy, iz));
            Vec3d localPoint = lab.toLocal(new Vec3d(px, py, pz));
            command.add("point", Protocol.vector(localPoint.x, localPoint.y, localPoint.z));
            batches.computeIfAbsent(lab, ignored -> new JsonArray()).add(command);
        }
        batches.forEach(Lab::queueCouplingImpulses);
    }

    /**
     * Sends the current shape of every soft body to all players (once per server tick, after tick()).
     * The surface triangles are sent in full when a body appears and then every {@link #TRIS_EVERY_TICKS}
     * ticks, so a player who joins later gets them within a second. In between, a mesh carries an empty
     * triangle list and the client keeps the copy it has. An empty list is sent once when the last body
     * goes, so the clients drop their copies.
     */
    public static void publish(MinecraftServer server) {
        if (broken) return;
        if (count <= 0) {
            if (published) {
                sendToAll(server, List.of());
                published = false;
            }
            trisSent = Set.of();
            return;
        }
        try {
            JsonObject listed = call("list", new JsonObject());
            JsonArray bodies = listed.getAsJsonArray("bodies");
            Set<Integer> ids = new HashSet<>();
            for (JsonElement element : bodies) ids.add(element.getAsInt());
            boolean full = ticksSinceFullTris >= TRIS_EVERY_TICKS || !trisSent.containsAll(ids);
            List<FoxPhysNetwork.SoftMesh> meshes = new ArrayList<>();
            for (JsonElement element : bodies) {
                JsonObject request = new JsonObject();
                request.addProperty("id", element.getAsInt());
                if (!full) request.addProperty("tris", false);
                JsonObject shape = call("positions", request);
                int[] tris = full ? ints(shape.getAsJsonArray("tris")) : new int[0];
                meshes.add(new FoxPhysNetwork.SoftMesh(
                        shape.get("id").getAsInt(),
                        shape.get("preset").getAsString(),
                        floats(shape.getAsJsonArray("pos")),
                        tris));
            }
            sendToAll(server, meshes);
            published = !meshes.isEmpty();
            if (full) {
                trisSent = ids;
                ticksSinceFullTris = 0;
            } else {
                ticksSinceFullTris++;
            }
            if (viewFailures > 0) {
                FoxPhysMod.LOGGER.info("FoxPhys soft body view recovered after {} failed ticks", viewFailures);
                viewFailures = 0;
            }
        } catch (RuntimeException e) {
            // A failed tick is retried on the next one. The first failure of a streak is logged with its stack.
            trisSent = Set.of();
            if (viewFailures++ == 0) FoxPhysMod.LOGGER.error("FoxPhys soft body view failed, retrying each tick: {}", e.getMessage(), e);
        }
    }

    private static void sendToAll(MinecraftServer server, List<FoxPhysNetwork.SoftMesh> meshes) {
        if (server == null) return;
        FoxPhysNetwork.SoftMeshes payload = new FoxPhysNetwork.SoftMeshes(meshes);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsFloat();
        return out;
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }

    public static boolean isBroken() {
        return broken;
    }

    public static void clearAll() {
        SoftAttachments.clear();
        META.clear();
        count = 0;
        ticksSinceFullTris = 0;
        trisSent = Set.of();
    }
}
