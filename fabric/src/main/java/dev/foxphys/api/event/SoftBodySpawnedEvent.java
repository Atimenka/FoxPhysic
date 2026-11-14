package dev.foxphys.api.event;

/** Fired after a soft body was created in the native soft-body registry. */
public final class SoftBodySpawnedEvent extends FoxPhysEvent {
    private final int bodyId;
    private final String preset;

    public SoftBodySpawnedEvent(int bodyId, String preset) {
        this.bodyId = bodyId;
        this.preset = preset;
    }

    public int bodyId() { return bodyId; }
    public String preset() { return preset; }
}
