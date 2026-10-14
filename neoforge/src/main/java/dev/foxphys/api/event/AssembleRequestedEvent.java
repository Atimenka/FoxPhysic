package dev.foxphys.api.event;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired before blocks are moved into a new physics body. Nothing has changed yet.
 * Cancelling refuses the assembly: the blocks stay in the world, and the caller gets no body.
 * The cell list is the requested cells in world coordinates; blocks that cannot be converted
 * (fluids, block entities, unbreakable blocks, air) are skipped by FoxPhys after this event.
 */
public final class AssembleRequestedEvent extends FoxPhysEvent implements ICancellableEvent {
    private final ServerLevel level;
    private final List<BlockPos> cells;

    public AssembleRequestedEvent(ServerLevel level, List<BlockPos> cells) {
        this.level = level;
        this.cells = List.copyOf(cells);
    }

    public ServerLevel level() { return level; }

    /** Requested cells in world coordinates (unmodifiable). */
    public List<BlockPos> cells() { return cells; }
}
