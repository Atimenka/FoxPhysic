package dev.foxphys.fluid;

import dev.foxphys.net.FoxPhysNetwork;
import dev.foxphys.net.FoxPhysNetwork.FluidFrame;
import dev.foxphys.net.FoxPhysNetwork.FluidPoolFrame;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side registry of SPH water pools. Steps them once per server tick and sends the particle
 * positions to every player within {@link #VIEW_DISTANCE} blocks of a pool.
 */
public final class FluidWater {
    private FluidWater() {}

    public static final int MAX_POOLS = 8;
    private static final double VIEW_DISTANCE = 96.0;
    private static final double NEAR_DISTANCE = 64.0;
    private static final List<FluidPool> POOLS = new ArrayList<>();

    /** Creates a pool with its floor at {@code origin} (the block corner). Throws IllegalStateException on error. */
    public static synchronized FluidPool add(ServerLevel level, BlockPos origin, boolean dam) {
        if (POOLS.size() >= MAX_POOLS) throw new IllegalStateException("too many water pools (max " + MAX_POOLS + ")");
        FluidPool pool = FluidPool.create(level, origin, dam);
        POOLS.add(pool);
        return pool;
    }

    public static synchronized int count() { return POOLS.size(); }

    /** Removes every pool (server stop or a clear command). */
    public static synchronized void clear() {
        for (FluidPool pool : POOLS) pool.close();
        POOLS.clear();
    }

    /** Steps the pools that have a player nearby, drops closed ones, and publishes the frames. */
    public static synchronized void tick(MinecraftServer server) {
        Iterator<FluidPool> it = POOLS.iterator();
        while (it.hasNext()) {
            FluidPool pool = it.next();
            try {
                pool.advance(anyPlayerNear(server, pool, NEAR_DISTANCE));
            } catch (RuntimeException e) {
                dev.foxphys.FoxPhysMod.LOGGER.error("FoxPhys water pool {} disabled after an error", pool.id(), e);
                pool.close();
                it.remove();
            }
        }
        publish(server);
    }

    private static boolean anyPlayerNear(MinecraftServer server, FluidPool pool, double radius) {
        Vec3 c = centre(pool);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == pool.level() && p.position().distanceToSqr(c) < radius * radius) return true;
        }
        return false;
    }

    private static Vec3 centre(FluidPool pool) {
        BlockPos o = pool.origin();
        return new Vec3(o.getX() + FluidPool.SIZE / 2, o.getY() + FluidPool.SIZE / 2, o.getZ() + FluidPool.SIZE / 2);
    }

    private static void publish(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<FluidPoolFrame> frames = new ArrayList<>();
            for (FluidPool pool : POOLS) {
                if (pool.level() != player.level()) continue;
                if (player.position().distanceToSqr(centre(pool)) > VIEW_DISTANCE * VIEW_DISTANCE) continue;
                BlockPos o = pool.origin();
                frames.add(new FluidPoolFrame(pool.id(), o.getX(), o.getY(), o.getZ(), (float) FluidPool.DX, pool.local()));
            }
            FoxPhysNetwork.sendFluid(player, new FluidFrame(frames));
        }
    }
}
