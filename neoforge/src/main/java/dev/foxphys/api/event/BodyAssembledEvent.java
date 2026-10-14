package dev.foxphys.api.event;

import net.minecraft.server.level.ServerLevel;

/** Fired after blocks were moved into a new body and the body exists in the native world. */
public final class BodyAssembledEvent extends FoxPhysEvent {
    private final ServerLevel level;
    private final long bodyId;
    private final int blockCount;

    public BodyAssembledEvent(ServerLevel level, long bodyId, int blockCount) {
        this.level = level;
        this.bodyId = bodyId;
        this.blockCount = blockCount;
    }

    public ServerLevel level() { return level; }
    public long bodyId() { return bodyId; }
    /** Number of blocks that became part of the body. */
    public int blockCount() { return blockCount; }
}
