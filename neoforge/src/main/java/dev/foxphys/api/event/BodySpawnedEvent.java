package dev.foxphys.api.event;

import net.minecraft.server.level.ServerLevel;

/** Fired after a rigid body was added to a physics world (by the API or by /foxphys spawn). */
public final class BodySpawnedEvent extends FoxPhysEvent {
    private final ServerLevel level;
    private final long bodyId;
    private final String kind;

    public BodySpawnedEvent(ServerLevel level, long bodyId, String kind) {
        this.level = level;
        this.bodyId = bodyId;
        this.kind = kind;
    }

    public ServerLevel level() { return level; }
    public long bodyId() { return bodyId; }
    /** Body kind name: wood, stone, boat or wing. */
    public String kind() { return kind; }
}
