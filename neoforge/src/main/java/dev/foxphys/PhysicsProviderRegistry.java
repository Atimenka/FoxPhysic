package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.foxphys.api.BodyInfo;
import dev.foxphys.api.FoxPhysApi;
import dev.foxphys.api.IForceProvider;
import dev.foxphys.api.PhysicsWorld;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Internal dispatcher for the public block/block-entity force-provider API. */
public final class PhysicsProviderRegistry {
    private static final int MAX_ENTITY_SCAN_CELLS = 4096;
    private static final int MAX_COMMANDS_PER_TICK = 64;
    private static final Map<Block, IForceProvider> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<BlockEntityType<?>, IForceProvider> BLOCK_ENTITIES = new ConcurrentHashMap<>();

    private PhysicsProviderRegistry() {}

    public static void registerBlock(Block block, IForceProvider provider) { BLOCKS.put(block, provider); }
    public static boolean unregisterBlock(Block block) { return BLOCKS.remove(block) != null; }
    public static void registerBlockEntity(BlockEntityType<?> type, IForceProvider provider) { BLOCK_ENTITIES.put(type, provider); }
    public static boolean unregisterBlockEntity(BlockEntityType<?> type) { return BLOCK_ENTITIES.remove(type) != null; }
    public static int blockProviderCount() { return BLOCKS.size(); }
    public static int blockEntityProviderCount() { return BLOCK_ENTITIES.size(); }

    /** Called once per active dimension tick, before the worker advances. */
    public static void apply(ServerLevel level, Lab lab) {
        if (BLOCKS.isEmpty() && BLOCK_ENTITIES.isEmpty()) return;
        Protocol.Snapshot snapshot = lab.snapshot();
        if (snapshot == null || snapshot.bodies() == null || snapshot.bodies().isEmpty()) return;
        PhysicsWorld physics = FoxPhysApi.physics(level).orElse(null);
        if (physics == null) return;
        JsonArray commands = new JsonArray();
        for (Protocol.BodyState state : snapshot.bodies()) {
            if (commands.size() >= MAX_COMMANDS_PER_TICK) break;
            BodyInfo body = physics.body(state.id()).orElse(null);
            if (body == null) continue;
            if (!BLOCKS.isEmpty()) {
                for (Map.Entry<BlockPos, BlockState> cell : lab.assemblyCells(state.id()).entrySet()) {
                    IForceProvider provider = BLOCKS.get(cell.getValue().getBlock());
                    if (provider == null) continue;
                    BlockPos source = lab.origin().offset(cell.getKey());
                    sample(provider, new IForceProvider.Context(level, physics, body, source,
                            cell.getValue(), null, 0.05), lab, commands);
                    if (commands.size() >= MAX_COMMANDS_PER_TICK) break;
                }
            }
            if (!BLOCK_ENTITIES.isEmpty() && commands.size() < MAX_COMMANDS_PER_TICK) {
                sampleNearbyBlockEntities(level, lab, physics, body, commands);
            }
        }
        if (!commands.isEmpty()) lab.queueCouplingImpulses(commands);
    }

    private static void sampleNearbyBlockEntities(ServerLevel level, Lab lab, PhysicsWorld physics,
                                                   BodyInfo body, JsonArray commands) {
        AABB bounds = body.bounds();
        if (bounds == null) return;
        int x0 = floor(bounds.minX) - 1, y0 = floor(bounds.minY) - 1, z0 = floor(bounds.minZ) - 1;
        int x1 = floor(bounds.maxX) + 1, y1 = floor(bounds.maxY) + 1, z1 = floor(bounds.maxZ) + 1;
        long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells <= 0 || cells > MAX_ENTITY_SCAN_CELLS) return;
        for (int x = x0; x <= x1 && commands.size() < MAX_COMMANDS_PER_TICK; x++) {
            for (int y = y0; y <= y1 && commands.size() < MAX_COMMANDS_PER_TICK; y++) {
                for (int z = z0; z <= z1 && commands.size() < MAX_COMMANDS_PER_TICK; z++) {
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isOutsideBuildHeight(pos)) continue;
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (entity == null) continue;
                    IForceProvider provider = BLOCK_ENTITIES.get(entity.getType());
                    if (provider == null) continue;
                    sample(provider, new IForceProvider.Context(level, physics, body, pos,
                            entity.getBlockState(), entity, 0.05), lab, commands);
                }
            }
        }
    }

    private static void sample(IForceProvider provider, IForceProvider.Context context,
                               Lab lab, JsonArray commands) {
        if (commands.size() >= MAX_COMMANDS_PER_TICK) return;
        try {
            IForceProvider.Force force = provider.sample(context);
            if (force == null) return;
            if (force.force() != null) {
                if (!finite(force.force()) || (force.worldPoint() != null && !finite(force.worldPoint()))) {
                    FoxPhysMod.LOGGER.warn("FoxPhys ignored a non-finite block force provider result at {}", context.sourcePosition());
                    return;
                }
                JsonObject command = Protocol.op("apply_force");
                command.addProperty("id", context.body().id());
                command.add("force", Protocol.vector(force.force().x, force.force().y, force.force().z));
                if (force.worldPoint() != null) {
                    Vec3 point = lab.toLocal(force.worldPoint());
                    command.add("point", Protocol.vector(point.x, point.y, point.z));
                }
                command.addProperty("duration", context.deltaSeconds());
                commands.add(command);
            }
            if (force.torque() != null && commands.size() < MAX_COMMANDS_PER_TICK) {
                if (!finite(force.torque())) {
                    FoxPhysMod.LOGGER.warn("FoxPhys ignored a non-finite block torque provider result at {}", context.sourcePosition());
                    return;
                }
                JsonObject command = Protocol.op("apply_torque");
                command.addProperty("id", context.body().id());
                command.add("torque", Protocol.vector(force.torque().x, force.torque().y, force.torque().z));
                command.addProperty("duration", context.deltaSeconds());
                commands.add(command);
            }
        } catch (RuntimeException e) {
            FoxPhysMod.LOGGER.warn("FoxPhys block force provider failed at {}", context.sourcePosition(), e);
        }
    }

    private static int floor(double value) { return (int) Math.floor(value); }
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Math.abs(value.x) <= 2_000_000 && Math.abs(value.y) <= 2_000_000 && Math.abs(value.z) <= 2_000_000;
    }
}
