package dev.foxphys.api.event;

import net.minecraft.server.world.ServerWorld;

/** Fired once per server tick for each dimension that has a physics world, after the world stepped. */
public final class PhysicsWorldTickEvent extends FoxPhysEvent {
    private final ServerWorld level;

    public PhysicsWorldTickEvent(ServerWorld level) {
        this.level = level;
    }

    public ServerWorld level() { return level; }
}
