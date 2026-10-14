package dev.foxphys.client;

import dev.foxphys.block.PhysicsBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws the original block state of a physics block at its interpolated pose.
 *
 * Opaque, translucent and animated textures follow the vanilla block entity path (render layers
 * and sprite animation come from the block renderer). Emissive blocks are drawn at full light.
 * Sodium does not replace block entity rendering, so this path is the same with or without it.
 */
public final class PhysicsBlockRenderer implements BlockEntityRenderer<PhysicsBlockEntity> {
    public PhysicsBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PhysicsBlockEntity entity, float tickDelta, PoseStack matrices,
                       MultiBufferSource vertexConsumers, int light, int overlay) {
        BlockState state = entity.originalState();
        if (state.isAir()) return;
        BlockPos pos = entity.getBlockPos();
        Vec3 center = entity.centerAt(tickDelta);
        Quaternionf rotation = entity.rotationAt(tickDelta);

        matrices.pushPose();
        // The entity origin is the block's own cell; the cube is rotated about its centre.
        matrices.translate(center.x - pos.getX(), center.y - pos.getY(), center.z - pos.getZ());
        matrices.mulPose(rotation);
        matrices.translate(-0.5, -0.5, -0.5);
        int emitted = state.getLightEmission() > 0 ? LightTexture.FULL_BRIGHT : light;
        Minecraft.getInstance().getBlockRenderer()
                .renderSingleBlock(state, matrices, vertexConsumers, emitted, overlay);
        matrices.popPose();
    }

    /** The cube can swing outside its cell, so it is never culled by the cell's box. */
    @Override
    public boolean shouldRenderOffScreen(PhysicsBlockEntity entity) {
        return true;
    }
}
