package dev.foxphys.api.client;

/** Immutable client snapshot of one server-simulated soft-body surface. Positions are world-space xyz triples. */
public record SoftBodyFrame(int id, String preset, float[] positions, int[] triangles) {
    public SoftBodyFrame {
        positions = positions.clone();
        triangles = triangles.clone();
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] triangles() { return triangles.clone(); }
}
