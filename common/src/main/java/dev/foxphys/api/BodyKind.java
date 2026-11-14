package dev.foxphys.api;

/** Kinds of rigid body that {@link PhysicsWorld#spawnBody} creates. Each has a fixed built-in shape. */
public enum BodyKind {
    /** A 1 m oak-plank cube, about 550 kg/m^3 average. */
    WOOD("wood"),
    /** A 1 m stone cube, 2600 kg/m^3. */
    STONE("stone"),
    /** A 3 x 5 m hollow boat of planks. Buoyancy is material-only in this release. */
    BOAT("boat"),
    /** A thin wing with lift from a fixed airfoil model. */
    WING("wing");

    private final String id;

    BodyKind(String id) {
        this.id = id;
    }

    /** The name used by the native protocol and by /foxphys spawn. */
    public String id() {
        return id;
    }
}
