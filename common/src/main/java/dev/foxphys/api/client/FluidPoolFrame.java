package dev.foxphys.api.client;

/** Immutable client snapshot of one SPH pool; particle xyz positions are relative to its world-space anchor. */
public record FluidPoolFrame(int id, double x, double y, double z, float spacing, float[] positions) {
    public FluidPoolFrame {
        positions = positions.clone();
    }

    @Override public float[] positions() { return positions.clone(); }
}
