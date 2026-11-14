package dev.foxphys.api;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Supplies a bounded force and/or torque to a rigid body from a registered block or block entity.
 * FoxPhys calls providers on the server thread once per game tick for nearby active physics worlds.
 * Return {@code null} when this source has no effect on the body this tick.
 *
 * <p>For block registrations, the context position is the original world cell that was assembled;
 * the block entity is {@code null}. For block-entity registrations, the context carries the live
 * block entity and its current world position. A provider must not call world-mutating APIs.
 */
@FunctionalInterface
public interface IForceProvider {
    Force sample(Context context);

    /** Read-only input for one provider sample. */
    record Context(ServerWorld level, PhysicsWorld physicsWorld, BodyInfo body,
                   BlockPos sourcePosition, BlockState sourceState, BlockEntity blockEntity,
                   double deltaSeconds) {}

    /**
     * One force pulse in newtons and/or torque pulse in newton-metres. {@code worldPoint} is optional;
     * when omitted, force acts at the body's centre of mass. Pulses last {@link Context#deltaSeconds()}.
     */
    record Force(Vec3d force, Vec3d worldPoint, Vec3d torque) {}
}
