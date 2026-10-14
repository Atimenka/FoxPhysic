package dev.foxphys;

import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.loading.FMLPaths;
import dev.foxphys.block.PhysicsBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FoxPhysMod {
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
    private record ChunkCheck(ServerLevel world, ChunkPos pos, int[] ticksLeft) {
        ChunkCheck(ServerLevel world, ChunkPos pos) { this(world, pos, new int[]{200}); }
    }
    private static final java.util.Queue<ChunkCheck> CHUNK_CHECKS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Runs on the server thread. A chunk not yet in the cache is retried for up to 200 ticks; a later load queues it again. */
    static void processChunkChecks(LabManager manager) {
        for (ChunkCheck check : new ArrayList<>(CHUNK_CHECKS)) {
            LevelChunk chunk = check.world().getChunkSource().getChunkNow(check.pos().x, check.pos().z);
            if (chunk == null) {
                if (--check.ticksLeft()[0] <= 0) CHUNK_CHECKS.remove(check);
                continue;
            }
            CHUNK_CHECKS.remove(check);
            removeOrphanPhysicsBlocks(manager, check.world(), chunk);
        }
    }

    /** Removes physics block entities of a loaded chunk that the live lab does not own at that position. */
    static void removeOrphanPhysicsBlocks(LabManager manager, ServerLevel world, LevelChunk chunk) {
        List<BlockPos> stale = new ArrayList<>();
        Lab lab = manager.labFor(world);
        for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
            if (!(be instanceof PhysicsBlockEntity pb)) continue;
            if (lab == null || !lab.holdsPhysicsBlock(be.getBlockPos(), pb.bodyId(), pb.owner())) stale.add(be.getBlockPos().immutable());
        }
        for (BlockPos p : stale) world.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (!stale.isEmpty()) LOGGER.warn("FoxPhys: removed {} leftover physics blocks in chunk {}", stale.size(), chunk.getPos());
    }

    /**
     * Start-up wiring, NeoForge event bus. Called once from the mod constructor.
     * Physics block placement and the gravity gun are server-side; the bus is the game bus.
     */
    public static void init() {
        FoxPhysConfig config = FoxPhysConfig.load();
        LabManager manager = new LabManager(config);
        MANAGER = manager;
        Migration.migrateConfig(FMLPaths.CONFIGDIR.get());
        manager.interactions().register();
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                e -> Commands.register(e.getDispatcher(), manager));
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel world) manager.tick(world);
        });
        // Physics blocks belong to a body, not to the terrain: players cannot break them.
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, e -> {
            if (e.getState().is(FoxPhysBlocks.PHYSICS_BLOCK.get())) e.setCanceled(true);
        });
        // A physics block whose lab no longer holds it (crash, restart, released body) is a leftover: remove it.
        // Changing blocks inside the chunk-load callback deadlocks the chunk executor, so the check is queued
        // and runs on the server thread once the chunk is in the cache.
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel world && e.getChunk() instanceof LevelChunk chunk) {
                CHUNK_CHECKS.add(new ChunkCheck(world, chunk.getPos()));
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            processChunkChecks(manager);
            // Soft bodies follow their rigid bodies first, then step, then the shapes go to clients.
            SoftAttachments.tick();
            SoftBodies.setWind(Wind.at(e.getServer().overworld()));
            SoftBodies.tick();
            SoftBodies.publish();
            // SPH water pools: step the pools with a nearby player, then publish their particles.
            dev.foxphys.fluid.FluidWater.tick(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class,
                e -> Migration.migrateSaves(e.getServer().getWorldPath(LevelResource.ROOT)));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            manager.shutdown();
            dev.foxphys.fluid.FluidWater.clear();
        });
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            // A crash may have left saved display entities. Never treat them as
            // saved native bodies: discard stale visuals as their chunks load.
            if (e.loadedFromDisk() && e.getEntity().getTags().contains(Lab.TAG) && !manager.owns(e.getEntity().getUUID())) {
                e.getEntity().discard();
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, e -> {
            // Ownership tracks loaded instances, not UUIDs saved in unloaded
            // chunks. A later reload must not revive a duplicate lab visual.
            if (e.getEntity().getTags().contains(Lab.TAG)) manager.untrack(e.getEntity().getUUID());
        });
        LOGGER.info("FoxPhys alpha ready. Physics starts on first use: assemble blocks, use honey glue, or /foxphys spawn (operator permission required)");
    }
}
