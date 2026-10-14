package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.foxphys.block.PhysicsBlockEntity;
import dev.foxphys.mixin.BlockDisplayAccessor;
import dev.foxphys.mixin.DisplayAccessor;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import java.util.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import dev.foxphys.api.event.AssembleRequestedEvent;
import dev.foxphys.api.event.BodyAssembledEvent;
import dev.foxphys.api.event.BodyDisassembledEvent;
import dev.foxphys.api.event.BodySpawnedEvent;
import dev.foxphys.api.event.FoxPhysEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import static dev.foxphys.Protocol.*;

/** One physics world per dimension, created on first use (no fixed stand region).
 * All coordinates crossing JNI are relative to an integer Minecraft anchor (the first body's position).
 * Java only samples geometry and presents Rust results; it never integrates motion.
 */
public final class Lab implements AutoCloseable {
    public static final String TAG = "foxphys_lab";
    private final ServerWorld world;
    private final BlockPos origin;
    private final FoxPhysConfig config;
    private final LabManager manager;
    private final long handle;
    private final Map<Long, List<DisplayEntity.BlockDisplayEntity>> bodyVisuals = new HashMap<>();
    private final List<DisplayEntity.BlockDisplayEntity> tankVisuals = new ArrayList<>();
    private final List<DisplayEntity.BlockDisplayEntity> waterVisuals = new ArrayList<>();
    private final List<DisplayEntity.BlockDisplayEntity> markers = new ArrayList<>();
    private Snapshot snapshot;
    private long nextId = 1;
    private int ticks;
    /** Terrain box geometry (its JSON) -> native key. Native terrain mirrors this map. */
    private final Map<String, Long> terrainIds = new HashMap<>();
    private long nextTerrainId = 1;
    /** Blocks moved into physics bodies by /foxphys assemble, keyed by body id. */
    private final Map<Long, Assembly> assemblies = new LinkedHashMap<>();
    /** cells: lab-local block position -> original state. */
    /** simAt: simulation time of the first snapshot that contained the body. */
    /** blocks: true when the cells are drawn as PhysicsBlocks; false when they stay on block displays (attached to a body that was not assembled). */
    private record Assembly(Map<BlockPos, BlockState> cells, double simAt, boolean blocks) {}
    /** Identity of this lab. A physics block stores it; blocks of another (dead) lab are leftovers. */
    private final UUID token = UUID.randomUUID();
    /** body id -> cell (lab-local) -> world position of its PhysicsBlock. Only cells that got a block. */
    private final Map<Long, Map<BlockPos, BlockPos>> placedCells = new HashMap<>();
    /** body id -> cell -> block display, for cells that could not get a PhysicsBlock (occupied or unloaded). */
    private final Map<Long, Map<BlockPos, DisplayEntity.BlockDisplayEntity>> cellVisuals = new HashMap<>();
    private String waterCache;
    private String tankKey;
    private boolean failed;
    private boolean idle;
    private boolean scanSkipped;
    /** Set by assemble: the cells became bodies, so the terrain must drop them before the next step. */
    private boolean scanPending;
    /** Set by a synchronous call: published snapshots must reach a later physics step before they are used. */
    private boolean awaitingStep;
    private long syncSteps;
    private int staleSnapshots;
    /** True while the lab is paused because no player is near (not a manual /foxphys pause). */
    private boolean frozenByIdle;
    /** Fixed-step id of the last actor contact result that was applied to entities. */
    private long lastContactStep = -1;
    /** True when the last push carried actor boxes (so an empty push is needed when nobody is near). */
    private boolean actorsPushed;
    private boolean closed;

    public Lab(ServerWorld world, BlockPos origin, FoxPhysConfig config, LabManager manager) {
        this.world = world; this.origin = origin.toImmutable(); this.config = config; this.manager = manager;
        NativeLoader.ensureLoaded();
        handle = NativeBridge.workerCreate(config.nativeJson());
        snapshot = GSON.fromJson(NativeBridge.workerCall(handle, "{}"), Snapshot.class);
    }
    public BlockPos origin() { return origin; }
    public Snapshot snapshot() { return snapshot; }
    public boolean scanSkipped() { return scanSkipped; }
    public boolean failed() { return failed; }
    public float[] local(Vec3d point) {
        return new float[]{(float)(point.x-origin.getX()), (float)(point.y-origin.getY()), (float)(point.z-origin.getZ())};
    }
    /** A player within 64 blocks of the anchor or of any body keeps this world running (Sable-aware when installed). */
    private boolean playersNear() {
        List<Vec3d> points = new ArrayList<>();
        points.add(new Vec3d(origin.getX(), origin.getY(), origin.getZ()));
        if (snapshot != null) for (BodyState b : snapshot.bodies()) points.add(worldPoint(b.position()));
        return world.getPlayers().stream().anyMatch(p -> points.stream().anyMatch(c -> dev.foxphys.compat.SableBridge.distanceSquared(world, p.getPos(), c) < 64 * 64));
    }
    public void tick() {
        if (closed || failed) return;
        // Distant/unobserved labs keep running at the worker's idle rate. Nothing catches up.
        boolean near = playersNear();
        if (near == idle) {
            idle = !near;
            NativeBridge.workerSetIdle(handle, idle);
        }
        drainWorkerErrors();
        if (failed) return;
        try {
            // Bodies freeze while nobody is near: terrain is only maintained where players are.
            // Without this a body could fall through blocks placed while the lab was unobserved.
            if (!near && !frozenByIdle && !snapshot.paused()) setFrozen(true);
            else if (near && frozenByIdle) setFrozen(false);
            if (near) {
                ticks++;
                JsonObject request = new JsonObject();
                if (ticks == 1 || scanPending || ticks % config.environmentScanInterval == 0) {
                    scanEnvironment(request);
                    // A body from assemble reaches the snapshot one step later. Until every assembled body
                    // is in the snapshot its region has no terrain, so the scan stays pending; otherwise the
                    // body falls through the ground until the next interval scan.
                    scanPending = snapshot == null || snapshot.bodies().size() < assemblies.size();
                }
                // Actors and grabs are replaced every tick: they describe the current players.
                request.add("actors", actorBoxes());
                request.add("grabs", manager.interactions().grabsFor(this));
                NativeBridge.workerPush(handle, request.toString());
                actorsPushed = true;
            } else if (actorsPushed) {
                // Nobody is near: release actors and grabs so no stale box keeps holding bodies.
                JsonObject request = new JsonObject();
                request.add("actors", new JsonArray());
                request.add("grabs", new JsonArray());
                NativeBridge.workerPush(handle, request.toString());
                actorsPushed = false;
            }
            // Read even when nobody is near: status stays current and expired bodies are noticed.
            String json = NativeBridge.workerLatest(handle);
            if (json != null) {
                Snapshot latest = GSON.fromJson(json, Snapshot.class);
                if (latest == null || latest.abi() != NativeBridge.ABI) throw new IllegalStateException("Invalid native snapshot");
                // A published snapshot that predates the last synchronous command (import_state is applied
                // without a physics step) would put the old bodies back: skip it until a newer step is published.
                if (awaitingStep && latest.fixedSteps() <= syncSteps && ++staleSnapshots < 20) latest = null; // paused world: stop waiting
                else { awaitingStep = false; staleSnapshots = 0; }
                if (latest != null) {
                    snapshot = latest;
                    reconcileBodies(latest);
                    applyContacts(latest);
                    if (near) updateBodyBlocks();
                    if (near && ticks % config.visualUpdateInterval == 0) updateVisuals();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            FoxPhysMod.LOGGER.error("FoxPhys physics world disabled after an error; restart the server to re-create it", e);
        }
    }
    /** Metres per tick that a standing entity is carried by the body it stands on. */
    private static final double CARRY_SECONDS = 0.05;
    /** Living entities pushed by bodies: boxes in lab coordinates, at most MAX_ACTORS. */
    private static final int MAX_ACTORS = 64;
    private JsonArray actorBoxes() {
        JsonArray list = new JsonArray();
        Vec3d center = new Vec3d(origin.getX() + 8, origin.getY() + 10, origin.getZ() + 8);
        Box area = new Box(center.x - 48, center.y - 48, center.z - 48, center.x + 48, center.y + 48, center.z + 48);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, Lab::isActor)) {
            if (list.size() >= MAX_ACTORS) break;
            Box b = e.getBoundingBox();
            JsonObject a = new JsonObject();
            // Entity ids are non-negative; +1 keeps the key non-zero as the native side requires.
            a.addProperty("key", (long) e.getId() + 1L);
            a.add("center", vector(b.getCenter().x - origin.getX(), b.getCenter().y - origin.getY(), b.getCenter().z - origin.getZ()));
            a.add("half", vector(clampHalf((b.maxX - b.minX) / 2), clampHalf((b.maxY - b.minY) / 2), clampHalf((b.maxZ - b.minZ) / 2)));
            a.addProperty("mass", 80.0);
            list.add(a);
        }
        return list;
    }
    private static boolean isActor(LivingEntity e) {
        return e.isAlive() && !e.isSpectator();
    }
    private static double clampHalf(double v) {
        return Math.max(0.05, Math.min(4.0, v));
    }
    /**
     * Moves entities out of bodies. Each contact result is applied once (by fixed-step id), so a
     * paused world does not push anybody repeatedly.
     */
    private void applyContacts(Snapshot s) {
        if (s.contacts() == null || s.contactStep() <= lastContactStep) return;
        lastContactStep = s.contactStep();

        for (ActorContact c : s.contacts()) {
            if (!(world.getEntityById((int) (c.actor() - 1L)) instanceof LivingEntity living) || !living.isAlive()) continue;
            float[] d = c.correction(), carry = c.carry();
            // A body above an entity pushes it down, but the entity is held by the ground: the
            // downward part is not applied (the body receives the matching impulse in Rust instead).
            // Carry from a moving body still applies, so an entity riding a body moves with it.
            double dx = d[0] + carry[0] * CARRY_SECONDS;
            double dy = Math.max(d[1], 0.0) + carry[1] * CARRY_SECONDS;
            double dz = d[2] + carry[2] * CARRY_SECONDS;
            if (dx * dx + dy * dy + dz * dz < 1e-8 && !c.standing()) continue;
            double x = living.getX() + dx, y = living.getY() + dy, z = living.getZ() + dz;
            if (living instanceof ServerPlayerEntity player) {
                player.networkHandler.requestTeleport(x, y, z, player.getYaw(), player.getPitch());
            } else {
                living.setPosition(x, y, z);
            }
            if (c.standing()) {
                living.fallDistance = 0;
                living.setOnGround(true);
                Vec3d v = living.getVelocity();
                // Sneaking holds the player on the body: no horizontal momentum of its own. The carry
                // above still moves the player with the body.
                double vx = living.isSneaking() ? 0 : v.x;
                double vz = living.isSneaking() ? 0 : v.z;
                living.setVelocity(vx, Math.max(0, v.y), vz);
                living.velocityModified = true;
            }
        }
    }
    /** Applies the newest contact result if it has not been applied yet. Called from PlayerEntity.tick (HEAD). */
    public void applyLatestContacts() {
        if (closed || failed || snapshot == null) return;
        applyContacts(snapshot);
    }
    /** True when the physics block at p belongs to this lab's body cell (used to clean leftovers after a chunk load). */
    public boolean holdsPhysicsBlock(BlockPos p, long bodyId, UUID owner) {
        if (!token.equals(owner)) return false;
        Map<BlockPos, BlockPos> cells = placedCells.get(bodyId);
        return cells != null && cells.containsValue(p);
    }
    /** Applies an impulse (N*s) to a body at a world point. Synchronous: throws if rejected. */
    public void impulse(long id, Vec3d impulseWorld, Vec3d pointWorld) {
        JsonObject command = op("impulse");
        command.addProperty("id", id);
        command.add("impulse", vector(impulseWorld.x, impulseWorld.y, impulseWorld.z));
        Vec3d local = toLocal(pointWorld);
        command.add("point", vector(local.x, local.y, local.z));
        commands(command);
    }
    /** Freezes (true) or resumes (false) the simulation. Bodies keep their poses while paused. */
    public void setPaused(boolean paused) {
        JsonObject command = op("pause");
        command.addProperty("paused", paused);
        commands(command);
    }
    /** Sets the wind velocity (m/s, world axes) applied to every body. */
    public void setWind(Vec3d velocity) {
        JsonObject command = op("set_wind");
        command.add("velocity", vector(velocity.x, velocity.y, velocity.z));
        commands(command);
    }
    /** Sets a body's pose (centre of mass and quaternion x, y, z, w, lab-local) and stops it. Synchronous. */
    public void setPose(long id, float[] position, float[] rotation) {
        JsonObject command = op("set_pose");
        command.addProperty("id", id);
        command.add("position", vector(position[0], position[1], position[2]));
        command.add("rotation", vector(rotation[0], rotation[1], rotation[2], rotation[3]));
        commands(command);
    }
    public Vec3d toLocal(Vec3d world) { return world.subtract(origin.getX(), origin.getY(), origin.getZ()); }
    public Vec3d toWorld(Vec3d local) { return local.add(origin.getX(), origin.getY(), origin.getZ()); }
    /** Nearest body whose box lies within `radius` of the world point, or null. */
    public BodyState bodyNear(Vec3d worldPoint, double radius) {
        if (snapshot == null) return null;
        Vec3d p = toLocal(worldPoint);
        BodyState best = null; double bestD = radius;
        for (BodyState b : snapshot.bodies()) {
            if (b.aabbMin() == null || b.aabbMax() == null) continue;
            double dx = Math.max(0, Math.max(b.aabbMin()[0] - p.x, p.x - b.aabbMax()[0]));
            double dy = Math.max(0, Math.max(b.aabbMin()[1] - p.y, p.y - b.aabbMax()[1]));
            double dz = Math.max(0, Math.max(b.aabbMin()[2] - p.z, p.z - b.aabbMax()[2]));
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= bestD) { bestD = d; best = b; }
        }
        return best;
    }
    private void setFrozen(boolean value) {
        JsonObject command = op("pause");
        command.addProperty("paused", value);
        commands(command);
        frozenByIdle = value;
    }
    /**
     * A body that was present and then vanished from a snapshot newer than its creation was removed
     * natively (Rust drops bodies beyond 128 m). Its blocks go back to the recorded cells instead of
     * being lost. Snapshots older than the creation are ignored, so a stale read cannot restore blocks
     * of a live body.
     */
    private void reconcileBodies(Snapshot latest) {
        Set<Long> present = new HashSet<>();
        for (BodyState b : latest.bodies()) present.add(b.id());
        Iterator<Map.Entry<Long, Assembly>> it = assemblies.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Assembly> e = it.next();
            if (present.contains(e.getKey()) || latest.simSeconds() <= e.getValue().simAt()) continue;
            it.remove();
            releaseBody(e.getKey());
            int placed = 0, dropped = 0;
            for (Map.Entry<BlockPos, BlockState> c : e.getValue().cells().entrySet()) {
                BlockPos p = origin.add(c.getKey().getX(), c.getKey().getY(), c.getKey().getZ());
                if (!world.isOutOfHeightLimit(p)) world.getChunk(p.getX() >> 4, p.getZ() >> 4); // load it: blocks must not be lost
                if (place(c.getKey(), c.getValue())) placed++; else dropped++;
            }
            FoxPhysMod.LOGGER.warn("FoxPhys: body #{} left the physics world (beyond 128 m); its blocks returned to their original cells: {} placed, {} dropped as items", e.getKey(), placed, dropped);
        }
    }
    private void drainWorkerErrors() {
        String error = NativeBridge.workerTakeError(handle);
        if (error == null || error.isEmpty()) return;
        if (error.startsWith("F:")) {
            failed = true;
            FoxPhysMod.LOGGER.error("FoxPhys physics stopped: {}", error.substring(2));
        } else {
            // A rejected input (for example invalid terrain). The world keeps running.
            FoxPhysMod.LOGGER.warn("FoxPhys input rejected: {}", error.substring(2));
        }
    }
    /** Collision scan regions in lab coordinates: the base box plus a margin around every body. */
    private static final int SCAN_MARGIN = 2;
    private static final double GRAVITY = 9.81;
    // The physics world spans 128 m around its anchor (Rust drops bodies beyond it), so terrain
    // must be sampled to the same bound; a 64 m clamp left far bodies without colliders.
    private static final int SCAN_REACH = 128;
    private static final long SCAN_BUDGET = 60_000L;
    private void scanEnvironment(JsonObject request) {
        List<int[]> regions = new ArrayList<>();
        if (snapshot != null) {
            for (BodyState body : snapshot.bodies()) {
                if (body.aabbMin() == null || body.aabbMax() == null) continue;
                // Terrain must cover where the body can be before the next scan: its motion over that time
                // (velocity, plus free fall downwards). With a scan every tick this adds almost nothing.
                double seconds = (config.environmentScanInterval + 1) / 20.0; // server ticks are 20 per second
                float[] v = body.velocity() == null ? new float[3] : body.velocity();
                int[] r = new int[6];
                for (int a = 0; a < 3; a++) {
                    double lo = Math.max(0, -v[a]) * seconds + (a == 1 ? 0.5 * GRAVITY * seconds * seconds : 0);
                    double hi = Math.max(0, v[a]) * seconds;
                    r[a] = clamp((int) Math.floor(body.aabbMin()[a] - SCAN_MARGIN - lo), -SCAN_REACH, SCAN_REACH);
                    r[a + 3] = clamp((int) Math.ceil(body.aabbMax()[a] + SCAN_MARGIN + hi), -SCAN_REACH, SCAN_REACH);
                }
                regions.add(r);
            }
        }
        Map<String, JsonObject> terrain = new LinkedHashMap<>();
        JsonArray water = new JsonArray();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Set<Long> seen = new HashSet<>();
        long budget = SCAN_BUDGET;
        scanSkipped = false;
        for (int[] r : regions) {
            long volume = (long) (r[3] - r[0]) * (r[4] - r[1]) * (r[5] - r[2]);
            if (volume <= 0) continue;
            if (volume > budget) {
                // Bodies beyond the scan budget get no new terrain this scan; reported by /foxphys status.
                scanSkipped = true;
                continue;
            }
            budget -= volume;
            for (int x = r[0]; x < r[3]; x++) for (int z = r[2]; z < r[5]; z++) for (int y = r[1]; y < r[4]; y++) {
                if (!seen.add(packKey(x, y, z))) continue;
                pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                if (world.isOutOfHeightLimit(pos) || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                BlockState state = world.getBlockState(pos);
                // Physics blocks belong to bodies (native colliders). Scanning them would collide a body with itself.
                if (state.isOf(FoxPhysBlocks.PHYSICS_BLOCK)) continue;
                for (Box box : state.getCollisionShape(world, pos).getBoundingBoxes()) {
                    double sx = box.maxX - box.minX, sy = box.maxY - box.minY, sz = box.maxZ - box.minZ;
                    if (sx < 0.001 || sy < 0.001 || sz < 0.001) continue; // native rejects boxes below 1 mm
                    if (terrain.size() >= 16384) throw new IllegalStateException("Terrain exceeds laboratory collider budget");
                    JsonObject c = new JsonObject();
                    c.add("center", vector(x + (box.minX + box.maxX) / 2, y + (box.minY + box.maxY) / 2, z + (box.minZ + box.maxZ) / 2));
                    c.add("size", vector(sx, sy, sz));
                    terrain.putIfAbsent(c.toString(), c);
                }
                var fluid = state.getFluidState();
                if (fluid.isIn(FluidTags.WATER)) {
                    float height = fluid.getHeight(world, pos);
                    if (height > 0) {
                        JsonObject cell = new JsonObject(); cell.add("cell", indices(x, y, z)); cell.addProperty("height", height);
                        // Vanilla water is sampled as a static medium in this alpha.
                        // Dynamic flow velocities come from the Rust tank solver.
                        cell.add("velocity", vector(0, 0, 0)); water.add(cell);
                    }
                }
            }
        }
        JsonArray commands = new JsonArray();
        diffTerrain(terrain, commands);
        if (commands.size() > 0) request.add("commands", commands);
        String waterJson = water.toString();
        if (!waterJson.equals(waterCache)) { request.add("water_cells", water); waterCache = waterJson; }
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long packKey(int x, int y, int z) {
        return ((long) (x + 4096) & 0x1FFFL) << 26 | ((long) (y + 4096) & 0x1FFFL) << 13 | ((long) (z + 4096) & 0x1FFFL);
    }
    private Snapshot call(JsonObject request, boolean present) {
        if (closed) throw new IllegalStateException("Lab is closed");
        snapshot = GSON.fromJson(NativeBridge.workerCall(handle, request.toString()), Snapshot.class);
        if (snapshot == null || snapshot.abi() != NativeBridge.ABI) throw new IllegalStateException("Invalid native snapshot");
        syncSteps = snapshot.fixedSteps();
        awaitingStep = true;
        staleSnapshots = 0;
        if (present) updateVisuals();
        return snapshot;
    }
    public ServerWorld world() { return world; }
    /** Dynamic state (bodies, springs, joints, wind) as the Rust SavedState JSON. Terrain and water are not saved. */
    public JsonObject exportState() {
        if (closed || failed) throw new IllegalStateException("Физика не запущена");
        JsonObject request = new JsonObject(); JsonArray list = new JsonArray();
        list.add(op("export_state")); request.add("commands", list);
        String json = NativeBridge.workerCall(handle, request.toString());
        JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        JsonObject result = root.getAsJsonArray("results").get(0).getAsJsonObject();
        if (!result.get("ok").getAsBoolean()) throw new IllegalStateException(result.get("message").getAsString());
        snapshot = GSON.fromJson(json, Snapshot.class);
        return result.getAsJsonObject("state");
    }
    /** Replaces bodies, springs and joints with a saved state. Rolled back on the Rust side if invalid. */
    public String importState(JsonObject state) {
        if (closed || failed) throw new IllegalStateException("Физика не запущена");
        JsonObject request = new JsonObject(); JsonArray list = new JsonArray();
        JsonObject command = op("import_state"); command.add("state", state); list.add(command);
        request.add("commands", list);
        Snapshot result = call(request, true);
        for (Result r : result.results()) if (!r.ok()) throw new IllegalArgumentException(r.message());
        scanPending = true; // the imported bodies need terrain under them before they fall: scan on the next tick
        return result.results().get(0).message();
    }
    public void commands(JsonObject... commands) {
        if (failed) throw new IllegalStateException("Physics world failed; restart the server");
        JsonObject request=new JsonObject(); JsonArray list=new JsonArray();
        for (JsonObject c:commands) list.add(c); request.add("commands",list);
        Snapshot result=call(request,true);
        for (Result r:result.results()) if (!r.ok()) throw new IllegalArgumentException(r.message());
    }
    public long spawn(String kind, float[] position) {
        long id=nextId++;
        JsonObject body=new JsonObject(); body.addProperty("id",id); body.add("position",GSON.toJsonTree(position));
        JsonArray parts=new JsonArray();
        switch (kind) {
            case "wood" -> parts.add(part(0,0,0,1,1,1,550,"minecraft:oak_planks"));
            case "stone" -> parts.add(part(0,0,0,1,1,1,2600,"minecraft:stone"));
            case "boat" -> {
                for (int x=-1;x<=1;x++) for (int z=-2;z<=2;z++) parts.add(part(x,0,z,1,1,1,550,"minecraft:oak_planks"));
                for (int x=-1;x<=1;x++) for (int z=-2;z<=2;z++) {
                    if (Math.abs(x)==1 || Math.abs(z)==2) parts.add(part(x,1,z,1,1,1,550,"minecraft:oak_planks"));
                }
                // Material-only buoyancy: enclosed air/flooding is NOT implemented yet.
            }
            case "wing" -> {
                parts.add(part(0,0,0,4,0.12,1.2,450,"minecraft:spruce_planks"));
                body.add("rotation",vector(-Math.sin(0.06),0,0,Math.cos(0.06)));
                JsonObject wing=new JsonObject();
                wing.add("center",vector(0,0,0)); wing.add("span",vector(1,0,0));
                wing.add("chord",vector(0,0,1)); wing.add("normal",vector(0,1,0));
                wing.addProperty("area",4.8); wing.addProperty("aspect_ratio",16.0/4.8); body.add("wing",wing);
            }
            default -> throw new IllegalArgumentException("Types: wood, stone, boat, wing");
        }
        body.add("parts",parts);
        JsonObject command=op("add_body"); command.add("body",body); commands(command);
        FoxPhysEvents.BODY_SPAWNED.invoker().accept(new BodySpawnedEvent(world, id, kind));
        return id;
    }
    public long springDemo() {
        long id=nextId++;
        JsonObject body=new JsonObject(); body.addProperty("id",id); body.add("position",vector(3,12,3));
        JsonArray parts=new JsonArray(); parts.add(part(0,0,0,0.5,0.5,0.5,7800,"minecraft:iron_block")); body.add("parts",parts);
        JsonObject add=op("add_body"); add.add("body",body);
        JsonObject spring=new JsonObject(); spring.addProperty("id",id); spring.add("a",com.google.gson.JsonNull.INSTANCE);
        spring.addProperty("b",id); spring.add("anchor_a",vector(3,16,3)); spring.add("anchor_b",vector(0,0,0));
        spring.addProperty("rest_length",2); spring.addProperty("stiffness",6000); spring.addProperty("damping",1400);
        JsonObject connect=op("add_spring"); connect.add("spring",spring); commands(add,connect);
        markers.add(boxDisplay(Blocks.GOLD_BLOCK.getDefaultState(),new float[]{3,16,3},new float[]{0.3f,0.3f,0.3f}));
        return id;
    }
    public void water(boolean damBreak) {
        TankSpec spec=new TankSpec(new float[]{2,4,2},12,12,1,damBreak?3:2,damBreak?0.35f:2,0.001f,0.04f);
        JsonObject command=op("set_tank"); command.add("tank",GSON.toJsonTree(spec)); commands(command);
    }
    private static JsonObject part(double x,double y,double z,double sx,double sy,double sz,double density,String block) {
        JsonObject p=new JsonObject(); p.add("offset",vector(x,y,z));p.add("size",vector(sx,sy,sz));p.addProperty("density",density);
        p.addProperty("block",block);return p;
    }
    private void updateVisuals() {
        Set<Long> active=new HashSet<>();
        for (BodyState body:snapshot.bodies()) {
            active.add(body.id());
            Assembly record=assemblies.get(body.id());
            if (record!=null && record.blocks()) {
                // Drawn by its PhysicsBlocks (and cell displays for unplaced cells): no part displays.
                List<DisplayEntity.BlockDisplayEntity> old=bodyVisuals.remove(body.id());
                if (old!=null) removeAll(old);
                continue;
            }
            List<DisplayEntity.BlockDisplayEntity> displays=bodyVisuals.get(body.id());
            if (displays==null || displays.size()!=body.parts().size() || displays.stream().anyMatch(d->d.isRemoved())) {
                if (displays!=null) removeAll(displays);
                displays=new ArrayList<>();
                for (Part p:body.parts()) {
                    Identifier blockId=Identifier.tryParse(p.block());
                    BlockState block=blockId==null?Blocks.OAK_PLANKS.getDefaultState():Registries.BLOCK.get(blockId).getDefaultState();
                    displays.add(createDisplay(block,worldPoint(body.position())));
                }
                bodyVisuals.put(body.id(),displays);
            }
            float[] rotation=body.rotation(); Quaternionf q=new Quaternionf(rotation[0],rotation[1],rotation[2],rotation[3]);
            for (int i=0;i<displays.size();i++) {
                var display=displays.get(i); var p=body.parts().get(i);
                float[] s=p.size();float[] off=p.offset();
                Vector3f translation=new Vector3f(off[0]-s[0]/2,off[1]-s[1]/2,off[2]-s[2]/2);
                q.transform(translation);
                display.setPosition(worldPoint(body.position()));
                transform(display,translation,new Quaternionf(q),new Vector3f(s[0],s[1],s[2]));
            }
        }
        for (Long id:new ArrayList<>(bodyVisuals.keySet())) {
            if (!active.contains(id)) removeAll(bodyVisuals.remove(id));
        }
        updateTank();
    }
    private void updateTank() {
        TankState tank=snapshot.tank();
        if (tank==null) { removeAll(tankVisuals); removeAll(waterVisuals);tankKey=null;return; }
        TankSpec s=tank.spec(); String key=GSON.toJson(s);
        if (!key.equals(tankKey) || tankVisuals.stream().anyMatch(d->d.isRemoved()) || waterVisuals.stream().anyMatch(d->d.isRemoved())) {
            removeAll(tankVisuals);removeAll(waterVisuals);tankKey=key;
            float x=s.origin()[0],y=s.origin()[1],z=s.origin()[2];float w=s.nx()*s.dx(),d=s.nz()*s.dx(),h=10;
            tankVisuals.add(boxDisplay(Blocks.GRAY_CONCRETE.getDefaultState(),new float[]{x+w/2,y-0.25f,z+d/2},new float[]{w+0.4f,0.5f,d+0.4f}));
            tankVisuals.add(boxDisplay(Blocks.GLASS.getDefaultState(),new float[]{x-0.1f,y+h/2,z+d/2},new float[]{0.2f,h,d+0.4f}));
            tankVisuals.add(boxDisplay(Blocks.GLASS.getDefaultState(),new float[]{x+w+0.1f,y+h/2,z+d/2},new float[]{0.2f,h,d+0.4f}));
            tankVisuals.add(boxDisplay(Blocks.GLASS.getDefaultState(),new float[]{x+w/2,y+h/2,z-0.1f},new float[]{w,h,0.2f}));
            tankVisuals.add(boxDisplay(Blocks.GLASS.getDefaultState(),new float[]{x+w/2,y+h/2,z+d+0.1f},new float[]{w,h,0.2f}));
            int stride=config.waterDisplayStride;
            for(int iz=0;iz<s.nz();iz+=stride) for(int ix=0;ix<s.nx();ix+=stride) {
                waterVisuals.add(createDisplay(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),
                        worldPoint(new float[]{x+ix*s.dx(),y,z+iz*s.dx()})));
            }
        }
        int displayIndex=0; int stride=config.waterDisplayStride;
        for (int z=0;z<s.nz();z+=stride) for (int x=0;x<s.nx();x+=stride) {
            int count=0;float depth=0;
            int nz=Math.min(stride,s.nz()-z),nx=Math.min(stride,s.nx()-x);
            for(int dz=0;dz<nz;dz++) for(int dx=0;dx<nx;dx++) {depth+=tank.depths()[(z+dz)*s.nx()+x+dx];count++;}
            // Render averaging only. Physics uses the full native cell resolution.
            transform(waterVisuals.get(displayIndex++),new Vector3f(),new Quaternionf(),
                    new Vector3f(nx*s.dx(),Math.max(0.001f,depth/count),nz*s.dx()));
        }
    }
    private Vec3d worldPoint(float[] local) {return new Vec3d(origin.getX()+local[0],origin.getY()+local[1],origin.getZ()+local[2]);}
    private DisplayEntity.BlockDisplayEntity createDisplay(BlockState state,Vec3d position) {
        var display=new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,world);
        display.setPosition(position);display.setNoGravity(true);display.setInvulnerable(true);display.addCommandTag(TAG);
        ((BlockDisplayAccessor)display).foxphys$blockState(state);
        var access=(DisplayAccessor)display;
        // Client-side interpolation over one tick hides the 20 Hz steps of the server tick.
        access.foxphys$interpolationDuration(1);
        access.foxphys$teleportDuration(1);
        access.foxphys$viewRange(1.5f);access.foxphys$displayWidth(32);access.foxphys$displayHeight(32);
        display.ignoreCameraFrustum=true;
        manager.track(display.getUuid());
        if(!world.spawnEntity(display)) {manager.untrack(display.getUuid());throw new IllegalStateException("Cannot spawn display entity");}
        return display;
    }
    private DisplayEntity.BlockDisplayEntity boxDisplay(BlockState block,float[] center,float[] size) {
        var display=createDisplay(block,worldPoint(center));
        transform(display,new Vector3f(-size[0]/2,-size[1]/2,-size[2]/2),new Quaternionf(),new Vector3f(size[0],size[1],size[2]));
        return display;
    }
    private void transform(DisplayEntity.BlockDisplayEntity display,Vector3f translation,Quaternionf rotation,Vector3f scale) {
        var access=(DisplayAccessor)display;
        access.foxphys$transformation(new AffineTransformation(translation,rotation,scale,new Quaternionf()));
        access.foxphys$startInterpolation(0);
    }
    private void removeAll(List<DisplayEntity.BlockDisplayEntity> displays) {
        for(var d:displays) {manager.untrack(d.getUuid());d.discard();} displays.clear();
    }
    /** Sends only the difference against the terrain the native world already holds. */
    private void diffTerrain(Map<String, JsonObject> current, JsonArray commands) {
        JsonArray remove = new JsonArray();
        Iterator<Map.Entry<String, Long>> it = terrainIds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (!current.containsKey(e.getKey())) { remove.add(e.getValue()); it.remove(); }
        }
        JsonArray add = new JsonArray();
        for (Map.Entry<String, JsonObject> e : current.entrySet()) {
            if (terrainIds.containsKey(e.getKey())) continue;
            long id = nextTerrainId++;
            terrainIds.put(e.getKey(), id);
            JsonObject keyed = new JsonObject();
            keyed.addProperty("key", id);
            keyed.add("center", e.getValue().get("center"));
            keyed.add("size", e.getValue().get("size"));
            add.add(keyed);
        }
        if (add.size() == 0 && remove.size() == 0) return;
        JsonObject command = op("patch_terrain");
        command.add("add", add);
        command.add("remove", remove);
        commands.add(command);
    }
    /** Physics blocks and cell displays of every assembled body, from the latest snapshot. Called once per tick while players are near. */
    private void updateBodyBlocks() {
        if (snapshot == null) return;
        for (Map.Entry<Long, Assembly> e : assemblies.entrySet()) {
            if (!e.getValue().blocks()) continue;
            BodyState body = body(e.getKey());
            if (body == null) continue; // a missing body is handled by reconcileBodies
            placeBody(e.getKey(), body, e.getValue());
        }
        for (Long id : new ArrayList<>(placedCells.keySet())) if (!isBlockBody(id)) releaseBody(id);
        for (Long id : new ArrayList<>(cellVisuals.keySet())) if (!isBlockBody(id)) releaseVisuals(id);
    }
    private boolean isBlockBody(long id) {
        Assembly a = assemblies.get(id);
        return a != null && a.blocks();
    }
    /**
     * One body: every cell gets its world centre from the body pose (position + rotation applied to
     * the cell offset from the body centroid). Cells whose centre falls in a free cell get a
     * PhysicsBlock; the rest keep a block display at the same centre.
     */
    private void placeBody(long id, BodyState body, Assembly record) {
        float[] rot = body.rotation();
        float[] pos = body.position();
        Quaternionf q = new Quaternionf(rot[0], rot[1], rot[2], rot[3]);
        Map<BlockPos, BlockState> cells = record.cells();
        double cx = 0, cy = 0, cz = 0;
        for (BlockPos c : cells.keySet()) { cx += c.getX() + 0.5; cy += c.getY() + 0.5; cz += c.getZ() + 0.5; }
        int n = Math.max(1, cells.size());
        cx /= n; cy /= n; cz /= n;

        // Phase 1: centres and target cells. A target cell is claimed by one cell only.
        Map<BlockPos, Vec3d> centers = new LinkedHashMap<>();
        Map<BlockPos, double[]> offsets = new HashMap<>();
        Map<BlockPos, BlockPos> target = new LinkedHashMap<>();
        Set<BlockPos> claimed = new HashSet<>();
        for (BlockPos cell : cells.keySet()) {
            double[] off = {cell.getX() + 0.5 - cx, cell.getY() + 0.5 - cy, cell.getZ() + 0.5 - cz};
            Vector3f v = new Vector3f((float) off[0], (float) off[1], (float) off[2]);
            q.transform(v);
            Vec3d center = new Vec3d(origin.getX() + pos[0] + v.x, origin.getY() + pos[1] + v.y, origin.getZ() + pos[2] + v.z);
            centers.put(cell, center);
            offsets.put(cell, off);
            BlockPos at = BlockPos.ofFloored(center);
            if (claimable(at) && claimed.add(at)) target.put(cell, at);
        }

        Map<BlockPos, BlockPos> placed = placedCells.computeIfAbsent(id, k -> new HashMap<>());
        // Phase 2: blocks that move to another cell (or lose their cell) leave their old place first.
        Iterator<Map.Entry<BlockPos, BlockPos>> it = placed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockPos> p = it.next();
            if (!p.getValue().equals(target.get(p.getKey()))) {
                removeOwned(id, p.getValue());
                it.remove();
            }
        }
        // Phase 3: place or move. A cell that cannot take its block is left to its display.
        float[] rotation = {rot[0], rot[1], rot[2], rot[3]};
        for (Map.Entry<BlockPos, BlockPos> t : target.entrySet()) {
            BlockPos cell = t.getKey(), at = t.getValue();
            Vec3d center = centers.get(cell);
            BlockState current = world.getBlockState(at);
            if (current.isOf(FoxPhysBlocks.PHYSICS_BLOCK)) {
                if (world.getBlockEntity(at) instanceof PhysicsBlockEntity pb && pb.ownedBy(token, id)) {
                    pb.push(center, rotation);
                    placed.put(cell, at);
                    continue;
                }
            } else if (current.isAir()) {
                // Air only: replacing water or plants would destroy them, and nothing would give them back.
                world.setBlockState(at, FoxPhysBlocks.PHYSICS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
                if (world.getBlockEntity(at) instanceof PhysicsBlockEntity pb) {
                    pb.attach(token, id, offsets.get(cell), cells.get(cell), center, rotation);
                    placed.put(cell, at);
                    continue;
                }
            }
            placed.remove(cell);
        }
        // Phase 4: displays for the cells without a block; a placed cell drops its display.
        Map<BlockPos, DisplayEntity.BlockDisplayEntity> visuals = cellVisuals.computeIfAbsent(id, k -> new HashMap<>());
        for (BlockPos cell : cells.keySet()) {
            if (placed.containsKey(cell)) {
                DisplayEntity.BlockDisplayEntity old = visuals.remove(cell);
                if (old != null) discard(old);
                continue;
            }
            Vec3d center = centers.get(cell);
            DisplayEntity.BlockDisplayEntity d = visuals.get(cell);
            if (d == null || d.isRemoved()) {
                d = createDisplay(cells.get(cell), center);
                visuals.put(cell, d);
            }
            Vector3f t = new Vector3f(0.5f, 0.5f, 0.5f);
            q.transform(t);
            t.negate(); // the display turns about the cube centre, not its corner
            d.setPosition(center);
            transform(d, t, new Quaternionf(q), new Vector3f(1, 1, 1));
        }
        visuals.keySet().removeIf(cell -> {
            if (cells.containsKey(cell) && !placed.containsKey(cell)) return false;
            discard(visuals.get(cell));
            return true;
        });
    }
    /** A cell may take a block when its chunk is loaded and it is inside the world height. */
    private boolean claimable(BlockPos at) {
        return !world.isOutOfHeightLimit(at) && world.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4);
    }
    /** Removes the physics block at `at` if it belongs to this lab and body. */
    private void removeOwned(long id, BlockPos at) {
        if (!claimable(at)) return;
        if (world.getBlockEntity(at) instanceof PhysicsBlockEntity pb && pb.ownedBy(token, id)) {
            world.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
    }
    /** Removes every physics block and display of a body. The body's cells are not put back. */
    private void releaseBody(long id) {
        Map<BlockPos, BlockPos> placed = placedCells.remove(id);
        if (placed != null) for (BlockPos at : placed.values()) removeOwned(id, at);
        releaseVisuals(id);
    }
    private void releaseVisuals(long id) {
        Map<BlockPos, DisplayEntity.BlockDisplayEntity> visuals = cellVisuals.remove(id);
        if (visuals != null) for (DisplayEntity.BlockDisplayEntity d : visuals.values()) discard(d);
    }
    private void discard(DisplayEntity.BlockDisplayEntity d) {
        if (d == null) return;
        manager.untrack(d.getUuid());
        d.discard();
    }

    /** Body by id in the latest snapshot, or null. */
    public BodyState bodyById(long id) { return body(id); }
    private BodyState body(long id) {
        if (snapshot == null) return null;
        for (BodyState b : snapshot.bodies()) if (b.id() == id) return b;
        return null;
    }
    public int assemblyCount() { return assemblies.size(); }
    /** True when the body was made from blocks (it can be disassembled back into them). */
    public boolean isAssembly(long id) { return assemblies.containsKey(id); }
    /** Density in kg/m^3: the material table when it has the block, otherwise a guess from the block name. */
    public static double density(BlockState state) {
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String path = id.getPath();
        // 1. Densities registered through the API (full id, then path) always win.
        Double api = DensityTable.overridden(id.toString());
        if (api == null) api = DensityTable.overridden(path);
        if (api != null) return api;
        // 2. The per-block table (every vanilla block).
        BlockPhysics.Entry block = BlockPhysics.get(id.toString());
        if (block != null) return block.density();
        // 3. The older material table (full id, then path), then block-name heuristics.
        Double exact = DensityTable.get(id.toString());
        if (exact != null) return exact;
        Double tabled = DensityTable.get(path);
        if (tabled != null) return tabled;
        if (path.contains("glass")) return 2500;
        // Sandstone is rock, not sand: a stone-like value (estimate, until the datapack table covers it).
        if (path.endsWith("sandstone")) return 2400;
        if (path.contains("planks") || path.contains("log") || path.contains("wood") || path.contains("fence")) return 600;
        if (path.contains("leaves") || path.contains("wool") || path.contains("sponge")) return 150;
        if (path.contains("dirt") || path.contains("sand") || path.contains("gravel") || path.contains("grass") || path.contains("clay")) return 1600;
        // "ice" also matches "lattice": only real ice blocks (ice, packed_ice, blue_ice, frosted_ice).
        if (path.endsWith("ice") && !path.endsWith("lattice")) return 917;
        if (path.contains("iron") || path.contains("anvil")) return 7800;
        return 2600;
    }
    /**
     * Moves the blocks of a cube into one physics body (the /foxphys assemble command).
     */
    public String assemble(BlockPos min, int size) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = min.getX(); x < min.getX() + size; x++)
            for (int y = min.getY(); y < min.getY() + size; y++)
                for (int z = min.getZ(); z < min.getZ() + size; z++) cells.add(new BlockPos(x, y, z));
        return assembleCells(cells);
    }
    /** True when a block may become part of a body (fluids, block entities and unbreakable blocks stay). */
    public boolean convertible(BlockPos p) {
        if (world.isOutOfHeightLimit(p) || !world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return false;
        BlockState state = world.getBlockState(p);
        if (state.isAir()) return false;
        return state.getFluidState().isEmpty() && !state.hasBlockEntity() && state.getHardness(world, p) >= 0;
    }
    /**
     * Moves the given world cells into ONE new physics body. The blocks leave the world only after
     * the native side accepted the body, so a refusal changes nothing.
     */
    public String assembleCells(List<BlockPos> worldCells) {
        AssemblyResult result = assembleBody(worldCells);
        if (result == null) return "Сборка отменена другим модом.";
        return "Сборка #" + result.id() + ": блоков " + result.blocks() + ". Блоки перенесены в тело.";
    }
    /** A new body: its id and how many blocks it took. */
    public record AssemblyResult(long id, int blocks) {}
    /**
     * Assembles the cells into a body. Returns null when an {@link AssembleRequestedEvent} listener cancelled
     * the request; nothing changes then. Throws IllegalArgumentException when no block can be taken or the
     * native side refuses the body; a refusal leaves the blocks in place.
     */
    public AssemblyResult assembleBody(List<BlockPos> worldCells) {
        if (closed || failed) throw new IllegalStateException("Физика не запущена");
        AssembleRequestedEvent request = new AssembleRequestedEvent(world, worldCells);
        FoxPhysEvents.ASSEMBLE_REQUESTED.invoker().accept(request);
        if (request.isCanceled()) return null;
        Map<BlockPos, BlockState> taken = takeCells(worldCells, true);
        if (taken.isEmpty()) throw new IllegalArgumentException("Нет блоков, которые можно собрать");
        long id = nextId++;
        JsonObject command = op("assemble_body");
        command.addProperty("id", id);
        command.add("cells", cellsJson(taken));
        assemblies.put(id, new Assembly(taken, snapshot == null ? 0 : snapshot.simSeconds(), true));
        try {
            commands(command); // a refusal throws here, before any block is touched
        } catch (RuntimeException e) {
            assemblies.remove(id);
            throw e;
        }
        removeFromWorld(taken);
        updateBodyBlocks(); // the cells appear as physics blocks at once, not one tick later
        scanPending = true; // until the next scan the old blocks would still be static terrain under the new body
        FoxPhysEvents.BODY_ASSEMBLED.invoker().accept(new BodyAssembledEvent(world, id, taken.size()));
        return new AssemblyResult(id, taken.size());
    }
    /** Adds the given world cells to an existing body (its orientation must be a multiple of 90 degrees). */
    public String attachCells(long id, List<BlockPos> worldCells) {
        if (closed || failed) throw new IllegalStateException("Физика не запущена");
        if (body(id) == null) throw new IllegalArgumentException("Тело #" + id + " отсутствует в физике");
        Map<BlockPos, BlockState> taken = takeCells(worldCells, false);
        if (taken.isEmpty()) throw new IllegalArgumentException("Нет блоков, которые можно прикрепить");
        JsonObject command = op("attach_cells");
        command.addProperty("id", id);
        command.add("cells", cellsJson(taken));
        commands(command);
        removeFromWorld(taken);
        Assembly record = assemblies.computeIfAbsent(id, k -> new Assembly(new LinkedHashMap<>(), snapshot.simSeconds(), false));
        record.cells().putAll(taken);
        updateBodyBlocks();
        scanPending = true;
        return "Тело #" + id + ": прикреплено блоков " + taken.size() + ".";
    }
    /** Lab-local cells of the given world positions that may be converted. Skips the rest. */
    private Map<BlockPos, BlockState> takeCells(List<BlockPos> worldCells, boolean requireNew) {
        Map<BlockPos, BlockState> taken = new LinkedHashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos p : worldCells) {
            if (!seen.add(p) || !convertible(p)) continue;
            taken.put(new BlockPos(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ()), world.getBlockState(p));
        }
        if (taken.size() > Assembler.MAX_ASSEMBLE_CELLS) throw new IllegalArgumentException("Слишком много блоков за раз: " + taken.size() + " (лимит " + Assembler.MAX_ASSEMBLE_CELLS + ")");
        int physical = 0;
        for (Assembly a : assemblies.values()) physical += a.cells().size();
        if (physical + taken.size() > Assembler.MAX_PHYSICAL_CELLS)
            throw new IllegalArgumentException("Лимит физических блоков в мире: " + Assembler.MAX_PHYSICAL_CELLS + " (сейчас " + physical + ", добавить " + taken.size() + ")");
        return taken;
    }
    /** Cell records for the native assembler. Full cubes carry no shape; other blocks carry their collision boxes. */
    private JsonArray cellsJson(Map<BlockPos, BlockState> taken) {
        JsonArray cells = new JsonArray();
        for (Map.Entry<BlockPos, BlockState> e : taken.entrySet()) {
            BlockPos local = e.getKey();
            JsonObject cell = new JsonObject();
            cell.add("cell", indices(local.getX(), local.getY(), local.getZ()));
            cell.addProperty("density", density(e.getValue()));
            cell.addProperty("block", Registries.BLOCK.getId(e.getValue().getBlock()).toString());
            JsonArray boxes = shapeBoxes(e.getValue(), origin.add(local.getX(), local.getY(), local.getZ()));
            if (boxes != null) cell.add("boxes", boxes);
            cells.add(cell);
        }
        return cells;
    }
    /**
     * Collision boxes of a block in cell-local units [0,1]^3, or null for a full cube.
     * An empty list means the block has no collider. More than 8 boxes fall back to one bounding box.
     */
    private JsonArray shapeBoxes(BlockState state, BlockPos worldPos) {
        List<Box> list = new ArrayList<>();
        for (Box b : state.getCollisionShape(world, worldPos).getBoundingBoxes()) {
            if (b.maxX - b.minX < 0.001 || b.maxY - b.minY < 0.001 || b.maxZ - b.minZ < 0.001) continue;
            list.add(b);
        }
        if (list.size() == 1 && isUnitCube(list.get(0))) return null;
        if (list.size() > 8) {
            double x0 = 1, y0 = 1, z0 = 1, x1 = 0, y1 = 0, z1 = 0;
            for (Box b : list) {
                x0 = Math.min(x0, b.minX); y0 = Math.min(y0, b.minY); z0 = Math.min(z0, b.minZ);
                x1 = Math.max(x1, b.maxX); y1 = Math.max(y1, b.maxY); z1 = Math.max(z1, b.maxZ);
            }
            list = List.of(new Box(x0, y0, z0, x1, y1, z1));
        }
        JsonArray out = new JsonArray();
        for (Box b : list) {
            JsonArray a = new JsonArray();
            for (double v : new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}) {
                a.add(Math.max(0, Math.min(1, v)));
            }
            out.add(a);
        }
        return out;
    }
    private static boolean isUnitCube(Box b) {
        return Math.abs(b.minX) < 1e-6 && Math.abs(b.minY) < 1e-6 && Math.abs(b.minZ) < 1e-6
                && Math.abs(b.maxX - 1) < 1e-6 && Math.abs(b.maxY - 1) < 1e-6 && Math.abs(b.maxZ - 1) < 1e-6;
    }
    private void removeFromWorld(Map<BlockPos, BlockState> taken) {
        for (BlockPos local : taken.keySet())
            world.setBlockState(origin.add(local.getX(), local.getY(), local.getZ()), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }
    /**
     * Returns the blocks of a body to the world. The body must be unrotated; a translated body is
     * snapped to whole blocks on the native side and its cells are matched to the recorded shape.
     */
    public String disassemble(long id) {
        if (closed || failed) throw new IllegalStateException("Физика не запущена");
        Assembly record = assemblies.get(id);
        if (record == null) throw new IllegalArgumentException("Нет сборки #" + id + " в этом мире");
        BodyState body = body(id);
        if (body == null) throw new IllegalArgumentException("Тело #" + id + " отсутствует в физике");
        if (Math.abs(body.rotation()[3]) < 0.999f) throw new IllegalArgumentException("Тело повёрнуто: разборка возможна только без поворота");
        JsonObject command = op("disassemble_body");
        command.addProperty("id", id);
        command.addProperty("snap", true);
        JsonObject request = new JsonObject();
        JsonArray list = new JsonArray(); list.add(command); request.add("commands", list);
        Snapshot result = call(request, true);
        Result r = result.results().get(0);
        if (!r.ok()) throw new IllegalArgumentException(r.message());
        assemblies.remove(id); // the body is gone natively now: its blocks must not be lost
        releaseBody(id);
        // Blocks go back shifted by the body's whole-block displacement since assembly. The body
        // origin started at the centroid of its cells, so the shift follows from its pose alone.
        BlockPos shift = poseShift(record.cells().keySet(), body.position());
        int placed = 0, dropped = 0;
        List<BlockPos> returned = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : record.cells().entrySet()) {
            BlockPos target = e.getKey().add(shift.getX(), shift.getY(), shift.getZ());
            if (place(target, e.getValue())) {
                placed++;
                returned.add(origin.add(target.getX(), target.getY(), target.getZ()));
            } else dropped++;
        }
        FoxPhysEvents.BODY_DISASSEMBLED.invoker().accept(new BodyDisassembledEvent(world, id, returned, dropped));
        return "Тело #" + id + " разобрано: блоков возвращено " + placed + (dropped > 0 ? ", выпало предметами " + dropped : "");
    }
    /** Whole-block shift of a body since assembly, from its origin and the centroid of its cells. */
    private static BlockPos poseShift(Set<BlockPos> cells, float[] position) {
        double cx = 0, cy = 0, cz = 0;
        for (BlockPos p : cells) { cx += p.getX() + 0.5; cy += p.getY() + 0.5; cz += p.getZ() + 0.5; }
        int n = Math.max(1, cells.size());
        cx /= n; cy /= n; cz /= n;
        double dx = position[0] - cx, dy = position[1] - cy, dz = position[2] - cz;
        if (Math.abs(dx - Math.round(dx)) > 0.05 || Math.abs(dy - Math.round(dy)) > 0.05 || Math.abs(dz - Math.round(dz)) > 0.05) {
            FoxPhysMod.LOGGER.warn("FoxPhys: body moved off the block grid by ({}, {}, {}); blocks are placed at the nearest cells", dx, dy, dz);
        }
        return new BlockPos((int) Math.round(dx), (int) Math.round(dy), (int) Math.round(dz));
    }
    /** Puts a block back. An occupied cell drops the block as an item instead of destroying it. */
    private boolean place(BlockPos local, BlockState state) {
        BlockPos p = origin.add(local.getX(), local.getY(), local.getZ());
        if (!world.isOutOfHeightLimit(p) && world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
            BlockState current = world.getBlockState(p);
            if (current.isAir() || current.isReplaceable()) {
                world.setBlockState(p, state, Block.NOTIFY_ALL);
                return true;
            }
        }
        Block.dropStack(world, p, new ItemStack(state.getBlock()));
        return false;
    }
    /** Puts every recorded block back and forgets the records. Bodies are not touched. */
    public int undoAssemblies() {
        int n = 0;
        for (Long id : new ArrayList<>(assemblies.keySet())) releaseBody(id);
        for (Long id : new ArrayList<>(placedCells.keySet())) releaseBody(id);
        for (Assembly a : assemblies.values()) for (Map.Entry<BlockPos, BlockState> e : a.cells().entrySet()) { place(e.getKey(), e.getValue()); n++; }
        assemblies.clear();
        return n;
    }
    /** Records for the save file. Block states are saved by block id only. */
    public JsonArray assembliesJson() {
        JsonArray list = new JsonArray();
        for (Map.Entry<Long, Assembly> e : assemblies.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", e.getKey());
            JsonArray cells = new JsonArray();
            for (Map.Entry<BlockPos, BlockState> c : e.getValue().cells().entrySet()) {
                JsonObject cell = new JsonObject();
                cell.add("pos", indices(c.getKey().getX(), c.getKey().getY(), c.getKey().getZ()));
                cell.addProperty("block", Registries.BLOCK.getId(c.getValue().getBlock()).toString());
                cells.add(cell);
            }
            item.add("cells", cells);
            list.add(item);
        }
        return list;
    }
    /** Replaces the records after a successful state import. Blocks of the old records go back first. */
    public void loadAssemblies(JsonArray list) {
        undoAssemblies();
        if (list == null) return;
        for (JsonElement el : list) {
            JsonObject item = el.getAsJsonObject();
            long id = item.get("id").getAsLong();
            if (body(id) == null) { FoxPhysMod.LOGGER.warn("FoxPhys: saved assembly #{} has no body; its blocks are not restored", id); continue; }
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (JsonElement ce : item.getAsJsonArray("cells")) {
                JsonObject cell = ce.getAsJsonObject();
                JsonArray p = cell.getAsJsonArray("pos");
                Identifier blockId = Identifier.tryParse(cell.get("block").getAsString());
                BlockState state = blockId == null ? Blocks.OAK_PLANKS.getDefaultState() : Registries.BLOCK.get(blockId).getDefaultState();
                cells.put(new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()), state);
            }
            takeSavedCells(id, cells);
            assemblies.put(id, new Assembly(cells, snapshot.simSeconds(), true));
        }
    }

    /**
     * A restored body owns its cells again. After /foxphys clear the original blocks are back in those
     * cells; they must be taken again, or the cube would exist twice. Only a cell that still holds the
     * saved block is cleared; anything else is left alone and reported.
     */
    private void takeSavedCells(long id, Map<BlockPos, BlockState> cells) {
        int taken = 0;
        for (Map.Entry<BlockPos, BlockState> c : cells.entrySet()) {
            BlockPos at = origin.add(c.getKey().getX(), c.getKey().getY(), c.getKey().getZ());
            if (world.isOutOfHeightLimit(at) || !world.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4)) continue;
            BlockState now = world.getBlockState(at);
            if (now.isAir() || now.isOf(FoxPhysBlocks.PHYSICS_BLOCK)) continue;
            if (now.getBlock() == c.getValue().getBlock()) {
                world.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                taken++;
            } else {
                FoxPhysMod.LOGGER.warn("FoxPhys: restored assembly #{}: cell {} holds {} instead of {}; left as is",
                        id, at, now.getBlock(), c.getValue().getBlock());
            }
        }
        if (taken > 0) FoxPhysMod.LOGGER.info("FoxPhys: restored assembly #{} took {} blocks back from its cells", id, taken);
    }
    @Override public void close() { close(true); }
    /** undoAssemblies=false only after a successful save: the saved bodies keep their blocks. */
    public void close(boolean undoAssemblies) {
        if (closed) return;
        closed = true;
        if (undoAssemblies) {
            undoAssemblies();
        } else {
            // The saved bodies keep their cells in the save file; their physics blocks are left for the next load.
            for (Long id : new ArrayList<>(cellVisuals.keySet())) releaseVisuals(id);
            placedCells.clear();
            assemblies.clear();
        }
        for (Long id : new ArrayList<>(cellVisuals.keySet())) releaseVisuals(id);
        bodyVisuals.values().forEach(this::removeAll);bodyVisuals.clear();
        removeAll(tankVisuals);removeAll(waterVisuals);removeAll(markers);
        try {NativeBridge.workerDestroy(handle);} catch(RuntimeException | LinkageError e) {FoxPhysMod.LOGGER.warn("Native lab cleanup error",e);}
    }
}
