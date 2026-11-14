package dev.foxphys.fluid;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxphys.FoxPhysBlocks;
import dev.foxphys.Lab;
import dev.foxphys.Protocol;
import dev.foxphys.Protocol.BodyState;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One pool of SPH water (3D weakly compressible SPH, native side in fluid_world.rs).
 *
 * <p>The open-top pool is anchored at a world block corner. Nearby Minecraft VoxelShape boxes and
 * rigid-body AABBs are passed to the native solver. Particle contacts displace water, dynamic contacts
 * return impulses to the rigid world, and the solver applies a volume-based Archimedes force.
 */
public final class FluidPool {
    /** Server ticks are 50 ms of simulated time per call. */
    public static final double TICK_SECONDS = 0.05;
    /** Substep cap per tick. When the cap binds, the pool runs slower than real time; {@link #lag()} reports it. */
    public static final int MAX_STEPS = 60;
    /** Only sample static/dynamic geometry within this radius of the pool. */
    public static final double INTERACTION_RADIUS = 1.0;
    private static final int MAX_EXTERNAL_BOXES = 512;
    private static final int MAX_COUPLED_BODIES = 64;

    // 1.2 m pool with 15 cm particles gives 320 samples, enough volume for one wooden block to float.
    public static final double SIZE = 1.2;
    public static final double DX = 0.15;
    public static final double FILL_HEIGHT = 0.6;

    private final int id;
    private final ServerLevel level;
    private final BlockPos origin;
    private final String shape;
    private final int particles;
    private float[] local = new float[0];
    private double lag = 1.0;
    private double simTime;
    private boolean closed;

    private FluidPool(int id, ServerLevel level, BlockPos origin, String shape, int particles) {
        this.id = id;
        this.level = level;
        this.origin = origin.immutable();
        this.shape = shape;
        this.particles = particles;
    }

    /** Creates a pool. {@code dam}: a column in the left 40 % with twice the fill height. */
    public static FluidPool create(ServerLevel level, BlockPos origin, boolean dam) {
        NativeLoader.ensureLoaded();
        String shape = dam ? "dam" : "still";
        JsonObject req = new JsonObject();
        req.addProperty("op", "create");
        JsonArray size = new JsonArray();
        size.add(SIZE); size.add(SIZE); size.add(SIZE);
        req.add("size", size);
        req.addProperty("fill_height", FILL_HEIGHT);
        req.addProperty("dx", DX);
        req.addProperty("shape", shape);
        JsonObject r = call(req);
        FluidPool pool = new FluidPool(r.get("id").getAsInt(), level, origin, shape, r.get("particles").getAsInt());
        pool.advance(false, null); // read initial positions without stepping
        return pool;
    }

    private static JsonObject call(JsonObject request) {
        return JsonParser.parseString(NativeBridge.fluidExchange(request.toString())).getAsJsonObject();
    }

    /** Advances one tick when {@code near}; always refreshes the particle positions. */
    public void advance(boolean near, Lab lab) {
        if (closed) return;
        if (near) {
            JsonObject step = new JsonObject();
            step.addProperty("op", "step");
            step.addProperty("id", id);
            step.addProperty("seconds", TICK_SECONDS);
            step.addProperty("max_steps", MAX_STEPS);
            step.add("obstacles", nearbyBlockColliders());
            step.add("bodies", nearbyRigidBodies(lab));
            JsonObject r = call(step);
            double advanced = r.get("advanced").getAsDouble();
            lag = advanced / TICK_SECONDS;
            simTime = r.get("sim_time").getAsDouble();
            if (lab != null && r.has("body_impulses")) queueCouplingImpulses(r.getAsJsonArray("body_impulses"), lab);
        }
        JsonObject pos = new JsonObject();
        pos.addProperty("op", "positions");
        pos.addProperty("id", id);
        JsonArray xyz = call(pos).getAsJsonArray("xyz");
        float[] out = new float[xyz.size()];
        for (int i = 0; i < out.length; i++) out[i] = xyz.get(i).getAsFloat();
        local = out;
    }

    /** Nearby static Minecraft collision boxes, converted from block-local to pool-local metres. */
    private JsonArray nearbyBlockColliders() {
        JsonArray out = new JsonArray();
        int minX = (int) Math.floor(origin.getX() - INTERACTION_RADIUS);
        int minY = (int) Math.floor(origin.getY() - INTERACTION_RADIUS);
        int minZ = (int) Math.floor(origin.getZ() - INTERACTION_RADIUS);
        int maxX = (int) Math.floor(origin.getX() + SIZE + INTERACTION_RADIUS);
        int maxY = (int) Math.floor(origin.getY() + SIZE + INTERACTION_RADIUS);
        int maxZ = (int) Math.floor(origin.getZ() + SIZE + INTERACTION_RADIUS);
        scan:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isOutsideBuildHeight(pos) || !level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockState state = level.getBlockState(pos);
                    // These blocks are moving rigid-body visuals; their AABBs are supplied below.
                    if (state.is(FoxPhysBlocks.PHYSICS_BLOCK.get())) continue;
                    for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
                        if (out.size() >= MAX_EXTERNAL_BOXES) break scan;
                        double[] min = {x + box.minX - origin.getX(), y + box.minY - origin.getY(), z + box.minZ - origin.getZ()};
                        double[] max = {x + box.maxX - origin.getX(), y + box.maxY - origin.getY(), z + box.maxZ - origin.getZ()};
                        if (!nearPool(min, max)) continue;
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

    /** Rigid bodies are radius-filtered and capped to the closest 64 snapshot AABBs. */
    private JsonArray nearbyRigidBodies(Lab lab) {
        JsonArray out = new JsonArray();
        if (lab == null || lab.failed() || lab.snapshot() == null) return out;
        List<BodyState> candidates = new ArrayList<>();
        for (BodyState body : lab.snapshot().bodies()) {
            float[] min = body.aabbMin(), max = body.aabbMax();
            if (min == null || max == null || min.length < 3 || max.length < 3
                    || !(Float.isFinite(body.mass()) && body.mass() > 0.0f)) continue;
            double[] localMin = new double[3], localMax = new double[3];
            for (int k = 0; k < 3; k++) {
                int labOriginCoord = k == 0 ? lab.origin().getX() : k == 1 ? lab.origin().getY() : lab.origin().getZ();
                int poolOrigin = k == 0 ? origin.getX() : k == 1 ? origin.getY() : origin.getZ();
                localMin[k] = labOriginCoord + min[k] - poolOrigin;
                localMax[k] = labOriginCoord + max[k] - poolOrigin;
            }
            if (nearPool(localMin, localMax)) candidates.add(body);
        }
        final BlockPos labOriginPos = lab.origin();
        candidates.sort(Comparator.comparingDouble((BodyState body) -> {
            float[] min = body.aabbMin(), max = body.aabbMax();
            double cx = (min[0] + max[0]) * 0.5 + labOriginPos.getX() - origin.getX() - SIZE * 0.5;
            double cy = (min[1] + max[1]) * 0.5 + labOriginPos.getY() - origin.getY() - SIZE * 0.5;
            double cz = (min[2] + max[2]) * 0.5 + labOriginPos.getZ() - origin.getZ() - SIZE * 0.5;
            return cx * cx + cy * cy + cz * cz;
        }));
        for (int i = 0; i < Math.min(MAX_COUPLED_BODIES, candidates.size()); i++) {
            BodyState body = candidates.get(i);
            float[] min = body.aabbMin(), max = body.aabbMax(), velocity = body.velocity();
            if (velocity == null || velocity.length < 3) continue;
            double[] localMin = new double[3], localMax = new double[3];
            for (int k = 0; k < 3; k++) {
                int labOriginCoord = k == 0 ? labOriginPos.getX() : k == 1 ? labOriginPos.getY() : labOriginPos.getZ();
                int poolOrigin = k == 0 ? origin.getX() : k == 1 ? origin.getY() : origin.getZ();
                localMin[k] = labOriginCoord + min[k] - poolOrigin;
                localMax[k] = labOriginCoord + max[k] - poolOrigin;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", body.id());
            entry.add("min", Protocol.vector(localMin));
            entry.add("max", Protocol.vector(localMax));
            entry.add("velocity", Protocol.vector(velocity[0], velocity[1], velocity[2]));
            entry.addProperty("mass", body.mass());
            out.add(entry);
        }
        return out;
    }

    private boolean nearPool(double[] min, double[] max) {
        for (int k = 0; k < 3; k++) {
            if (max[k] < -INTERACTION_RADIUS || min[k] > SIZE + INTERACTION_RADIUS) return false;
        }
        return true;
    }

    /** Converts native pool-local feedback to one non-blocking, batched rigid-world command. */
    private void queueCouplingImpulses(JsonArray impulses, Lab lab) {
        if (impulses == null || impulses.isEmpty()) return;
        JsonArray commands = new JsonArray();
        for (JsonElement element : impulses) {
            if (!element.isJsonObject()) continue;
            JsonObject response = element.getAsJsonObject();
            JsonArray impulse = response.getAsJsonArray("impulse");
            JsonArray point = response.getAsJsonArray("point");
            if (impulse == null || point == null || impulse.size() != 3 || point.size() != 3) continue;
            double ix = impulse.get(0).getAsDouble(), iy = impulse.get(1).getAsDouble(), iz = impulse.get(2).getAsDouble();
            double px = origin.getX() + point.get(0).getAsDouble();
            double py = origin.getY() + point.get(1).getAsDouble();
            double pz = origin.getZ() + point.get(2).getAsDouble();
            if (!Double.isFinite(ix) || !Double.isFinite(iy) || !Double.isFinite(iz)
                    || !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) continue;
            JsonObject command = Protocol.op("impulse");
            command.addProperty("id", response.get("id").getAsLong());
            command.add("impulse", Protocol.vector(ix, iy, iz));
            Vec3 localPoint = lab.toLocal(new Vec3(px, py, pz));
            command.add("point", Protocol.vector(localPoint.x, localPoint.y, localPoint.z));
            commands.add(command);
        }
        if (!commands.isEmpty()) lab.queueCouplingImpulses(commands);
    }

    public void close() {
        if (closed) return;
        closed = true;
        JsonObject req = new JsonObject();
        req.addProperty("op", "remove");
        req.addProperty("id", id);
        try {
            call(req);
        } catch (RuntimeException e) {
            // The native registry may already be gone (shutdown); nothing else to release.
        }
    }

    public int id() { return id; }
    public ServerLevel level() { return level; }
    public BlockPos origin() { return origin; }
    public String shape() { return shape; }
    public int particles() { return particles; }
    /** Particle positions relative to the origin, x y z per particle. */
    public float[] local() { return local; }
    /** Fraction of the requested simulated time that the last tick managed (1 = real time). */
    public double lag() { return lag; }
    public double simTime() { return simTime; }
    public boolean closed() { return closed; }
}
