package dev.foxphys.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Fired when a dimension gets its physics world for the first time (or after a failed one was replaced). */
public final class PhysicsWorldCreatedEvent extends FoxPhysEvent {
    private final ServerLevel level;
    private final BlockPos origin;

    public PhysicsWorldCreatedEvent(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    public ServerLevel level() { return level; }
    /** Lab origin in world coordinates. */
    public BlockPos origin() { return origin; }
}
