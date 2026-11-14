package dev.foxphys.api.event;

import net.minecraft.server.world.ServerWorld;

/** Fired after blocks were moved into a new body and the body exists in the native world. */
public final class BodyAssembledEvent extends FoxPhysEvent {
    private final ServerWorld level;
    private final long bodyId;
    private final int blockCount;

    public BodyAssembledEvent(ServerWorld level, long bodyId, int blockCount) {
        this.level = level;
        this.bodyId = bodyId;
        this.blockCount = blockCount;
    }

    public ServerWorld level() { return level; }
    public long bodyId() { return bodyId; }
    /** Number of blocks that became part of the body. */
    public int blockCount() { return blockCount; }
}
