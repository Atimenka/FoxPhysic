package dev.foxphys.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
    record Context(ServerLevel level, PhysicsWorld physicsWorld, BodyInfo body,
                   BlockPos sourcePosition, BlockState sourceState, BlockEntity blockEntity,
                   double deltaSeconds) {}

    /**
     * One force pulse in newtons and/or torque pulse in newton-metres. {@code worldPoint} is optional;
     * when omitted, force acts at the body's centre of mass. Pulses last {@link Context#deltaSeconds()}.
     */
    record Force(Vec3 force, Vec3 worldPoint, Vec3 torque) {}
}
