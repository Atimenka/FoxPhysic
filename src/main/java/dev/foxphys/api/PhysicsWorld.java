package dev.foxphys.api;

import dev.foxphys.FoxPhysMod;
import dev.foxphys.Lab;
import dev.foxphys.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * A view of the rigid-body world of one dimension. Obtain it from {@link FoxPhysApi}. The view keeps
 * only the level; each call reads the current world, so it stays valid while the world is alive and
 * throws {@link IllegalStateException} after the world was closed or failed.
 *
 * <p>All coordinates are world coordinates unless a name says otherwise. Server thread only: every
 * method checks the thread and throws {@link IllegalStateException} when called from another one.
 */
public final class PhysicsWorld {
    private final ServerWorld level;

    PhysicsWorld(ServerWorld level) {
        this.level = level;
    }

    /** The dimension this world belongs to. */
    public ServerWorld level() {
        return level;
    }

    /** The lab origin in world coordinates. */
    public BlockPos origin() {
        return lab().origin();
    }

    /**
     * Adds a rigid body with its centre of mass at {@code worldCentre}. The body starts at rest.
     *
     * @return the new body id, also sent in {@link dev.foxphys.api.event.BodySpawnedEvent}
     * @throws IllegalArgumentException if the position is not finite or the native side refuses
     */
    public long spawnBody(BodyKind kind, Vec3d worldCentre) {
        if (kind == null) throw new IllegalArgumentException("kind is null");
        requireFinite(worldCentre, "worldCentre");
        Lab lab = lab();
        Vec3d local = lab.toLocal(worldCentre);
        return lab.spawn(kind.id(), new float[] {(float) local.x, (float) local.y, (float) local.z});
    }

    /**
     * Applies an impulse (N*s) at a world point. Synchronous: throws if the native side rejects it.
     *
     * @throws IllegalArgumentException if the body does not exist or the values are not finite
     */
    public void applyImpulse(long bodyId, Vec3d impulse, Vec3d worldPoint) {
        requireFinite(impulse, "impulse");
        requireFinite(worldPoint, "worldPoint");
        lab().impulse(bodyId, impulse, worldPoint);
    }

    /**
     * Moves a body to a pose and stops it. Synchronous.
     *
     * @param worldCentre centre of mass, world coordinates
     * @param quaternion  x, y, z, w; normalised here, so it does not need to be a unit quaternion
     */
    public void setPose(long bodyId, Vec3d worldCentre, double[] quaternion) {
        requireFinite(worldCentre, "worldCentre");
        if (quaternion == null || quaternion.length != 4) throw new IllegalArgumentException("quaternion needs x, y, z, w");
        double n = 0;
        for (double q : quaternion) {
            if (!Double.isFinite(q)) throw new IllegalArgumentException("quaternion is not finite");
            n += q * q;
        }
        if (n < 1e-12) throw new IllegalArgumentException("quaternion has zero length");
        n = Math.sqrt(n);
        Lab lab = lab();
        Vec3d local = lab.toLocal(worldCentre);
        lab.setPose(bodyId,
                new float[] {(float) local.x, (float) local.y, (float) local.z},
                new float[] {(float) (quaternion[0] / n), (float) (quaternion[1] / n),
                        (float) (quaternion[2] / n), (float) (quaternion[3] / n)});
    }

    /** A body by id, or empty when it does not exist in the last snapshot. */
    public Optional<BodyInfo> body(long bodyId) {
        Protocol.BodyState state = lab().bodyById(bodyId);
        return state == null ? Optional.empty() : Optional.of(toInfo(state, lab()));
    }

    /** Bodies whose bounds intersect a world-coordinate box, from the last snapshot. */
    public List<BodyInfo> bodiesIn(Box region) {
        if (region == null) throw new IllegalArgumentException("region is null");
        List<BodyInfo> out = new ArrayList<>();
        for (BodyInfo b : bodies()) {
            if (b.bounds() != null && b.bounds().intersects(region)) out.add(b);
        }
        return out;
    }

    /** Every rigid body in the last snapshot. */
    public List<BodyInfo> bodies() {
        Lab lab = lab();
        Protocol.Snapshot snap = lab.snapshot();
        if (snap == null) return List.of();
        List<BodyInfo> out = new ArrayList<>(snap.bodies().size());
        for (Protocol.BodyState b : snap.bodies()) out.add(toInfo(b, lab));
        return out;
    }

    /**
     * The body whose box is nearest to {@code worldPoint}, within {@code radius} metres. Empty if none.
     */
    public Optional<BodyInfo> nearestBody(Vec3d worldPoint, double radius) {
        requireFinite(worldPoint, "worldPoint");
        if (!(radius >= 0) || !Double.isFinite(radius)) throw new IllegalArgumentException("radius must be >= 0");
        Lab lab = lab();
        Protocol.BodyState b = lab.bodyNear(worldPoint, radius);
        return b == null ? Optional.empty() : Optional.of(toInfo(b, lab));
    }

    /**
     * Turns the blocks of a cube into one body, with the cube's minimum corner at {@code min}.
     *
     * @param size edge length in blocks, 1 or more
     * @return the body id, or empty when a {@link dev.foxphys.api.event.AssembleRequestedEvent} listener cancelled it
     * @throws IllegalArgumentException if no block can be taken or the limits of the world are reached
     */
    public OptionalLong assemble(BlockPos min, int size) {
        if (min == null) throw new IllegalArgumentException("min is null");
        if (size < 1 || size > 16) throw new IllegalArgumentException("size must be 1..16");
        List<BlockPos> cells = new ArrayList<>(size * size * size);
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++) cells.add(min.add(x, y, z));
        return assembleCells(cells);
    }

    /**
     * Turns the given world cells into one body. Cells that cannot be converted (air, fluids, block
     * entities, unbreakable blocks) are skipped. A refusal changes nothing.
     *
     * @return the body id, or empty when a listener cancelled the request
     */
    public OptionalLong assembleCells(Collection<BlockPos> worldCells) {
        if (worldCells == null) throw new IllegalArgumentException("worldCells is null");
        Lab.AssemblyResult result = lab().assembleBody(new ArrayList<>(worldCells));
        return result == null ? OptionalLong.empty() : OptionalLong.of(result.id());
    }

    /**
     * Takes a body apart and puts its blocks back, if it was made from blocks and is not rotated.
     * Fires {@link dev.foxphys.api.event.BodyDisassembledEvent}.
     *
     * @throws IllegalArgumentException if the body is unknown, is not an assembly, or is rotated
     */
    public void disassemble(long bodyId) {
        lab().disassemble(bodyId);
    }

    /** Freezes or resumes the simulation. Bodies keep their poses while paused. */
    public void setPaused(boolean paused) {
        lab().setPaused(paused);
    }

    /** True while the simulation is frozen (by this API, by a command, or because no player is near). */
    public boolean isPaused() {
        Protocol.Snapshot snap = lab().snapshot();
        return snap != null && snap.paused();
    }

    /** Sets wind velocity in m/s, world axes. Applied to every body. */
    public void setWind(Vec3d velocity) {
        requireFinite(velocity, "velocity");
        lab().setWind(velocity);
    }

    /** Counters of this world at the last completed step. */
    public WorldStats stats() {
        Lab lab = lab();
        Protocol.Snapshot snap = lab.snapshot();
        if (snap == null) return new WorldStats(0, 0, 0, lab.assemblyCount(), 0, 0, false);
        return new WorldStats(snap.simSeconds(), snap.fixedSteps(), snap.bodies().size(), lab.assemblyCount(),
                snap.staticColliders(), snap.lastStepUs(), snap.paused());
    }

    /** The lab as it is now. Fails when the world was closed or replaced, or on a foreign thread. */
    private Lab lab() {
        FoxPhysApi.requireServerThread(level);
        Lab lab = FoxPhysMod.manager().labFor(level);
        if (lab == null) throw new IllegalStateException("No physics world in " + level.getRegistryKey().getValue());
        if (lab.failed()) throw new IllegalStateException("Physics world failed; restart the server");
        return lab;
    }

    private static BodyInfo toInfo(Protocol.BodyState b, Lab lab) {
        Vec3d centre = lab.toWorld(new Vec3d(b.position()[0], b.position()[1], b.position()[2]));
        Vec3d velocity = new Vec3d(b.velocity()[0], b.velocity()[1], b.velocity()[2]);
        Vec3d angular = new Vec3d(b.angularVelocity()[0], b.angularVelocity()[1], b.angularVelocity()[2]);
        Box bounds = null;
        if (b.aabbMin() != null && b.aabbMax() != null) {
            bounds = new Box(lab.toWorld(new Vec3d(b.aabbMin()[0], b.aabbMin()[1], b.aabbMin()[2])),
                    lab.toWorld(new Vec3d(b.aabbMax()[0], b.aabbMax()[1], b.aabbMax()[2])));
        }
        double[] rotation = {b.rotation()[0], b.rotation()[1], b.rotation()[2], b.rotation()[3]};
        return new BodyInfo(b.id(), centre, rotation, b.mass(), velocity, angular, bounds, b.parts().size(),
                lab.isAssembly(b.id()));
    }

    private static void requireFinite(Vec3d v, String name) {
        if (v == null || !Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)) {
            throw new IllegalArgumentException(name + " must be a finite vector");
        }
    }
}
