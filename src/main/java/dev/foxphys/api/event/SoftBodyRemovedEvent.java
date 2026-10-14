package dev.foxphys.api.event;

/** Fired after a soft body was removed from the registry. */
public final class SoftBodyRemovedEvent extends FoxPhysEvent {
    private final int bodyId;

    public SoftBodyRemovedEvent(int bodyId) {
        this.bodyId = bodyId;
    }

    public int bodyId() { return bodyId; }
}
