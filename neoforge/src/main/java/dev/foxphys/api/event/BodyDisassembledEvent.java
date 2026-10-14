package dev.foxphys.api.event;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Fired after a body was taken apart and its blocks were put back into the world. */
public final class BodyDisassembledEvent extends FoxPhysEvent {
    private final ServerLevel level;
    private final long bodyId;
    private final List<BlockPos> returnedCells;
    private final int droppedBlocks;

    public BodyDisassembledEvent(ServerLevel level, long bodyId, List<BlockPos> returnedCells, int droppedBlocks) {
        this.level = level;
        this.bodyId = bodyId;
        this.returnedCells = List.copyOf(returnedCells);
        this.droppedBlocks = droppedBlocks;
    }

    public ServerLevel level() { return level; }
    public long bodyId() { return bodyId; }
    /** World positions where blocks were placed back (unmodifiable). */
    public List<BlockPos> returnedCells() { return returnedCells; }
    /** Blocks that could not be placed and dropped as items. */
    public int droppedBlocks() { return droppedBlocks; }
}
