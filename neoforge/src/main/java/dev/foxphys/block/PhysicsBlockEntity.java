package dev.foxphys.block;

import dev.foxphys.FoxPhysBlocks;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaternionf;

/**
 * One physics block of an assembled body, standing in the world at the cell that holds its centre.
 *
 * The pose (centre and rotation, previous and current tick) is pushed by the owning Lab every
 * tick. The client interpolates between the two poses in {@link dev.foxphys.client.PhysicsBlockRenderer}.
 * The collision shape is the original block's shape moved to the real centre of the body block.
 */
public final class PhysicsBlockEntity extends BlockEntity {
    /** Lab that owns this block. A block whose owner is not a live lab is removed when its chunk loads. */
    private UUID owner;
    private long bodyId;
    /** Centre of this block relative to the body centre, in body coordinates (unrotated). */
    private double[] offset = new double[3];
    private BlockState originalState = Blocks.AIR.defaultBlockState();
    private Vec3 prevCenter;
    private Vec3 curCenter;
    private float[] prevRotation = {0, 0, 0, 1};
    private float[] curRotation = {0, 0, 0, 1};
    private VoxelShape shape;

    public PhysicsBlockEntity(BlockPos pos, BlockState state) {
        super(FoxPhysBlocks.PHYSICS_BLOCK_ENTITY.get(), pos, state);
        prevCenter = curCenter = Vec3.atCenterOf(pos);
    }

    /** Sets the identity and the first pose. The first pose is not interpolated. */
    public void attach(UUID owner, long bodyId, double[] offset, BlockState original, Vec3 center, float[] rotation) {
        this.owner = owner;
        this.bodyId = bodyId;
        this.offset = offset.clone();
        this.originalState = original;
        prevCenter = curCenter = center;
        prevRotation = curRotation = rotation.clone();
        shape = null;
        setChanged();
        sync();
    }

    /** Smallest move (metres) that counts as a change; smaller jitter of a resting body is not sent. */
    private static final double MOVE_EPSILON = 1e-4;
    /** Smallest rotation change (dot product of unit quaternions) that counts as a change. */
    private static final double ROTATION_EPSILON = 1e-7;

    /**
     * Moves the previous pose to the current one and sets a new current pose. Sent to clients.
     * A body at rest sends nothing per tick: the first still tick after a move sends one update
     * that makes previous and current pose equal, then the block stays silent until it moves again.
     */
    public void push(Vec3 center, float[] rotation) {
        boolean moved = curCenter.distanceToSqr(center) > MOVE_EPSILON * MOVE_EPSILON
                || rotationDot(curRotation, rotation) < 1.0 - ROTATION_EPSILON;
        if (!moved) {
            boolean settled = prevCenter.distanceToSqr(curCenter) == 0.0
                    && rotationDot(prevRotation, curRotation) >= 1.0 - ROTATION_EPSILON;
            if (settled) return;
            prevCenter = curCenter;
            prevRotation = curRotation;
            sync();
            return;
        }
        prevCenter = curCenter;
        prevRotation = curRotation;
        curCenter = center;
        curRotation = rotation.clone();
        shape = null;
        sync();
    }

    private static double rotationDot(float[] a, float[] b) {
        return Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
    }

    public boolean ownedBy(UUID lab, long id) {
        return lab != null && lab.equals(owner) && bodyId == id;
    }

    public UUID owner() { return owner; }
    public long bodyId() { return bodyId; }
    public double[] offset() { return offset.clone(); }
    public BlockState originalState() { return originalState; }

    /** Centre of the block at a partial tick (0 = previous pose, 1 = current pose). */
    public Vec3 centerAt(float tickDelta) {
        return prevCenter.lerp(curCenter, tickDelta);
    }

    /** Rotation at a partial tick: spherical interpolation between the two poses. */
    public Quaternionf rotationAt(float tickDelta) {
        Quaternionf from = new Quaternionf(prevRotation[0], prevRotation[1], prevRotation[2], prevRotation[3]);
        Quaternionf to = new Quaternionf(curRotation[0], curRotation[1], curRotation[2], curRotation[3]);
        return from.slerp(to, tickDelta);
    }

    /**
     * Collision and outline shape of the original block, moved so that its cube sits on the real
     * centre of the body block. Shapes that leave the cell are fine: vanilla queries the neighbours.
     */
    public VoxelShape collisionShape() {
        if (shape != null) return shape;
        if (originalState.isAir()) return shape = Shapes.empty();
        BlockPos pos = getBlockPos();
        VoxelShape base = getLevel() != null
                ? originalState.getCollisionShape(getLevel(), pos)
                : Shapes.block();
        double dx = curCenter.x - (pos.getX() + 0.5);
        double dy = curCenter.y - (pos.getY() + 0.5);
        double dz = curCenter.z - (pos.getZ() + 0.5);
        shape = base.move(dx, dy, dz);
        return shape;
    }

    /** Pushes the current pose to clients that track this block. Only the block entity's data is sent. */
    private void sync() {
        if (getLevel() == null || getLevel().isClientSide()) return;
        var state = getBlockState();
        getLevel().sendBlockUpdated(getBlockPos(), state, state, Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (owner != null) nbt.putUUID("owner", owner);
        nbt.putLong("body", bodyId);
        putDoubles(nbt, "offset", offset);
        nbt.put("state", NbtUtils.writeBlockState(originalState));
        putDoubles(nbt, "prevCenter", new double[]{prevCenter.x, prevCenter.y, prevCenter.z});
        putDoubles(nbt, "curCenter", new double[]{curCenter.x, curCenter.y, curCenter.z});
        putDoubles(nbt, "prevRot", toDoubles(prevRotation));
        putDoubles(nbt, "curRot", toDoubles(curRotation));
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        owner = nbt.hasUUID("owner") ? nbt.getUUID("owner") : null;
        bodyId = nbt.getLong("body");
        double[] o = getDoubles(nbt, "offset", 3);
        if (o != null) offset = o;
        if (nbt.contains("state")) {
            originalState = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), nbt.getCompound("state"));
        }
        double[] p = getDoubles(nbt, "prevCenter", 3);
        double[] c = getDoubles(nbt, "curCenter", 3);
        prevCenter = p != null ? new Vec3(p[0], p[1], p[2]) : Vec3.atCenterOf(getBlockPos());
        curCenter = c != null ? new Vec3(c[0], c[1], c[2]) : prevCenter;
        prevRotation = toFloats(getDoubles(nbt, "prevRot", 4), prevRotation);
        curRotation = toFloats(getDoubles(nbt, "curRot", 4), curRotation);
        shape = null;
    }

    /** Block entities loaded on the client. The Sodium mixin draws the ones Sodium's culling hides. */
    private static final Set<PhysicsBlockEntity> CLIENT_LIVE = ConcurrentHashMap.newKeySet();

    /** Live client-side physics blocks. Entries may be stale: check {@code getWorld().getBlockEntity(getPos()) == this}. */
    public static Collection<PhysicsBlockEntity> clientLive() {
        return CLIENT_LIVE;
    }

    @Override
    public void setLevel(Level world) {
        super.setLevel(world);
        if (world != null && world.isClientSide()) CLIENT_LIVE.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LIVE.remove(this);
    }

    /**
     * Chunk data and block entity updates carry this NBT to the client. The default is empty, so the
     * client never learned the original block state and drew nothing. The full NBT is sent here.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithFullMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** NBT has no double array here: values are stored as "<key>0", "<key>1", ... */
    private static void putDoubles(CompoundTag nbt, String key, double[] values) {
        for (int i = 0; i < values.length; i++) nbt.putDouble(key + i, values[i]);
    }

    private static double[] getDoubles(CompoundTag nbt, String key, int count) {
        if (!nbt.contains(key + "0")) return null;
        double[] values = new double[count];
        for (int i = 0; i < count; i++) values[i] = nbt.getDouble(key + i);
        return values;
    }

    private static double[] toDoubles(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

    private static float[] toFloats(double[] d, float[] fallback) {
        if (d == null) return fallback;
        return new float[]{(float) d[0], (float) d[1], (float) d[2], (float) d[3]};
    }
}
