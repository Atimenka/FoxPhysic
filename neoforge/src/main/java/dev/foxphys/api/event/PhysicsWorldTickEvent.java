package dev.foxphys.api.event;

import net.minecraft.server.level.ServerLevel;

/** Fired once per server tick for each dimension that has a physics world, after the world stepped. */
public final class PhysicsWorldTickEvent extends FoxPhysEvent {
    private final ServerLevel level;

    public PhysicsWorldTickEvent(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() { return level; }
}
