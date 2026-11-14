package dev.foxphys.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.AbstractBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * A block of a physics body while it is in the world. It has no look of its own: the renderer
 * draws the original block state at the body pose. Its collision and outline are the original
 * block's shape at the body pose, from {@link PhysicsBlockEntity}.
 *
 * Model rendering is requested, and the model is empty (assets/foxphys/models/block/physics_block_empty.json),
 * so the chunk mesh draws nothing for it.
 */
public final class PhysicsBlock extends BlockWithEntity {
    public PhysicsBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return createCodec(PhysicsBlock::new);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PhysicsBlockEntity(pos, state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeAt(world, pos);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeAt(world, pos);
    }

    @Override
    protected VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeAt(world, pos);
    }

    private static VoxelShape shapeAt(BlockView world, BlockPos pos) {
        // No block entity (for example while the chunk loads) means no collider: never a ghost cube.
        if (world.getBlockEntity(pos) instanceof PhysicsBlockEntity be) return be.collisionShape();
        return VoxelShapes.empty();
    }
}
