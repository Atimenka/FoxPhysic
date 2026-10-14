package dev.foxphys.client;

import dev.foxphys.block.PhysicsBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * Draws the original block state of a physics block at its interpolated pose.
 *
 * Opaque, translucent and animated textures follow the vanilla block entity path (render layers
 * and sprite animation come from the block renderer). Emissive blocks are drawn at full light.
 * Sodium does not replace block entity rendering, so this path is the same with or without it.
 */
public final class PhysicsBlockRenderer implements BlockEntityRenderer<PhysicsBlockEntity> {
    public PhysicsBlockRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(PhysicsBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        BlockState state = entity.originalState();
        if (state.isAir()) return;
        BlockPos pos = entity.getPos();
        Vec3d center = entity.centerAt(tickDelta);
        Quaternionf rotation = entity.rotationAt(tickDelta);

        matrices.push();
        // The entity origin is the block's own cell; the cube is rotated about its centre.
        matrices.translate(center.x - pos.getX(), center.y - pos.getY(), center.z - pos.getZ());
        matrices.multiply(rotation);
        matrices.translate(-0.5, -0.5, -0.5);
        int emitted = state.getLuminance() > 0 ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
        MinecraftClient.getInstance().getBlockRenderManager()
                .renderBlockAsEntity(state, matrices, vertexConsumers, emitted, overlay);
        matrices.pop();
    }

    /** The cube can swing outside its cell, so it is never culled by the cell's box. */
    @Override
    public boolean rendersOutsideBoundingBox(PhysicsBlockEntity entity) {
        return true;
    }
}
