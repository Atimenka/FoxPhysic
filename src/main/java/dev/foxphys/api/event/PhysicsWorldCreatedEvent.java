package dev.foxphys.api.event;

import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;

/** Fired when a dimension gets its physics world for the first time (or after a failed one was replaced). */
public final class PhysicsWorldCreatedEvent extends FoxPhysEvent {
    private final ServerWorld level;
    private final BlockPos origin;

    public PhysicsWorldCreatedEvent(ServerWorld level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    public ServerWorld level() { return level; }
    /** Lab origin in world coordinates. */
    public BlockPos origin() { return origin; }
}
