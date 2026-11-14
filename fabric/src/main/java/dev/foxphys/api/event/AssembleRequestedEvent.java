package dev.foxphys.api.event;

import java.util.List;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Fired before blocks are moved into a new physics body. Nothing has changed yet.
 * Cancelling refuses the assembly: the blocks stay in the world, and the caller gets no body.
 * The cell list is the requested cells in world coordinates; blocks that cannot be converted
 * (fluids, block entities, unbreakable blocks, air) are skipped by FoxPhys after this event.
 * All listeners run even after one cancelled; the assembly is refused if any of them did.
 */
public final class AssembleRequestedEvent extends FoxPhysEvent {
    private final ServerWorld level;
    private final List<BlockPos> cells;
    private boolean canceled;

    public AssembleRequestedEvent(ServerWorld level, List<BlockPos> cells) {
        this.level = level;
        this.cells = List.copyOf(cells);
    }

    public ServerWorld level() { return level; }

    /** Requested cells in world coordinates (unmodifiable). */
    public List<BlockPos> cells() { return cells; }

    /** True once a listener cancelled the request. */
    public boolean isCanceled() { return canceled; }

    /** Refuses the assembly. */
    public void setCanceled(boolean canceled) { this.canceled = canceled; }
}
