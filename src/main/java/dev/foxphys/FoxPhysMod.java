package dev.foxphys;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import dev.foxphys.block.PhysicsBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FoxPhysMod implements ModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("FoxPhys");
    /** One manager per JVM (one server). Set in onInitialize; mixins reach the interactions through it. */
    private static volatile LabManager MANAGER;
    public static LabManager manager() {
        LabManager m = MANAGER;
        if (m == null) throw new IllegalStateException("FoxPhys is not initialised");
        return m;
    }
    public static Interactions interactions() {return manager().interactions();}

    /** A chunk that loaded: its physics blocks are checked once the chunk is in the cache. */
    private record ChunkCheck(ServerWorld world, ChunkPos pos, int[] ticksLeft) {
        ChunkCheck(ServerWorld world, ChunkPos pos) { this(world, pos, new int[]{200}); }
    }
    private static final java.util.Queue<ChunkCheck> CHUNK_CHECKS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Runs on the server thread. A chunk not yet in the cache is retried for up to 200 ticks; a later load queues it again. */
    static void processChunkChecks(LabManager manager) {
        for (ChunkCheck check : new ArrayList<>(CHUNK_CHECKS)) {
            WorldChunk chunk = check.world().getChunkManager().getWorldChunk(check.pos().x, check.pos().z);
            if (chunk == null) {
                if (--check.ticksLeft()[0] <= 0) CHUNK_CHECKS.remove(check);
                continue;
            }
            CHUNK_CHECKS.remove(check);
            removeOrphanPhysicsBlocks(manager, check.world(), chunk);
        }
    }

    /** Removes physics block entities of a loaded chunk that the live lab does not own at that position. */
    static void removeOrphanPhysicsBlocks(LabManager manager, ServerWorld world, WorldChunk chunk) {
        List<BlockPos> stale = new ArrayList<>();
        Lab lab = manager.labFor(world);
        for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
            if (!(be instanceof PhysicsBlockEntity pb)) continue;
            if (lab == null || !lab.holdsPhysicsBlock(be.getPos(), pb.bodyId(), pb.owner())) stale.add(be.getPos().toImmutable());
        }
        for (BlockPos p : stale) world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        if (!stale.isEmpty()) LOGGER.warn("FoxPhys: removed {} leftover physics blocks in chunk {}", stale.size(), chunk.getPos());
    }

    @Override public void onInitialize() {
        Migration.migrateConfig(FabricLoader.getInstance().getConfigDir());
        FoxPhysConfig config=FoxPhysConfig.load();
        LabManager manager=new LabManager(config);
        MANAGER=manager;
        FoxPhysBlocks.register();
        manager.interactions().register();
        CommandRegistrationCallback.EVENT.register((dispatcher,registryAccess,environment)->Commands.register(dispatcher,manager));
        ServerTickEvents.END_WORLD_TICK.register(manager::tick);
        // Physics blocks belong to a body, not to the terrain: players cannot break them.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !state.isOf(FoxPhysBlocks.PHYSICS_BLOCK));
        // A physics block whose lab no longer holds it (crash, restart, released body) is a leftover: remove it.
        // Changing blocks inside the chunk-load callback deadlocks the chunk executor, so the check is queued
        // and runs on the server thread once the chunk is in the cache.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> CHUNK_CHECKS.add(new ChunkCheck(world, chunk.getPos())));
        ServerTickEvents.END_SERVER_TICK.register(server -> processChunkChecks(manager));
        // Soft bodies: attachments follow their rigid bodies, then wind and step, then the shapes go to clients.
        // SPH water pools step after that, with the same publish-once-per-tick rule.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SoftAttachments.tick();
            SoftBodies.setWind(Wind.at(server.getOverworld()));
            SoftBodies.tick();
            SoftBodies.publish(server);
            dev.foxphys.fluid.FluidWater.tick(server);
        });
        dev.foxphys.net.FoxPhysNetwork.registerTypes();
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                dev.foxphys.net.FoxPhysNetwork.AlignRequest.ID,
                (payload, context) -> context.server().execute(() -> interactions().align(context.player())));
        ServerLifecycleEvents.SERVER_STARTING.register(server->Migration.migrateSaves(server.getSavePath(WorldSavePath.ROOT)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            manager.shutdown();
            dev.foxphys.fluid.FluidWater.clear();
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            // A crash may have left saved display entities. Never treat them as
            // saved native bodies: discard stale visuals as their chunks load.
            if(entity.getCommandTags().contains(Lab.TAG) && !manager.owns(entity.getUuid())) entity.discard();
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->{
            // Ownership tracks loaded instances, not UUIDs saved in unloaded
            // chunks. A later reload must not revive a duplicate lab visual.
            if(entity.getCommandTags().contains(Lab.TAG)) manager.untrack(entity.getUuid());
        });
        LOGGER.info("FoxPhys alpha ready. Physics starts on first use: assemble blocks, use honey glue, or /foxphys spawn (operator permission required)");
    }
}
