package dev.foxphys.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockGetter;

/**
 * A block of a physics body while it is in the world. It has no look of its own: the renderer
 * draws the original block state at the body pose. Its collision and outline are the original
 * block's shape at the body pose, from {@link PhysicsBlockEntity}.
 *
 * Model rendering is requested, and the model is empty (assets/foxphys/models/block/physics_block_empty.json),
 * so the chunk mesh draws nothing for it.
 */
public final class PhysicsBlock extends BaseEntityBlock {
    public PhysicsBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(PhysicsBlock::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhysicsBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return shapeAt(world, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return shapeAt(world, pos);
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return shapeAt(world, pos);
    }

    private static VoxelShape shapeAt(BlockGetter world, BlockPos pos) {
        // No block entity (for example while the chunk loads) means no collider: never a ghost cube.
        if (world.getBlockEntity(pos) instanceof PhysicsBlockEntity be) return be.collisionShape();
        return Shapes.empty();
    }
}
