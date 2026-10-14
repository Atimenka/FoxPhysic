package dev.foxphys.mixin.client;

import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import dev.foxphys.block.PhysicsBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium draws global block entities only from sections it considers visible. A physics block is
 * drawn at its body's pose, so its cell's section can be hidden (occluded) while the cube is in the
 * open. After Sodium's pass, every live physics block whose cell Sodium did not draw is drawn here,
 * with the same transform Sodium uses for block entities.
 *
 * Sodium is not a compile dependency: the target is a string and {@link FoxPhysMixinPlugin} applies
 * this only when Sodium is loaded. require=0: a Sodium whose method differs is skipped.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumPhysicsBlockMixin {
    @Shadow
    public abstract boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2);

    @Inject(method = "renderGlobalBlockEntities", at = @At("TAIL"), require = 0)
    private void foxphys$drawHiddenPhysicsBlocks(MatrixStack matrices, BufferBuilderStorage buffers,
                                                 Long2ObjectMap<SortedSet<?>> breaking, float tickDelta,
                                                 VertexConsumerProvider.Immediate bufferSource,
                                                 double camX, double camY, double camZ,
                                                 BlockEntityRenderDispatcher dispatcher,
                                                 ClientPlayerEntity player, LocalBooleanRef glow,
                                                 CallbackInfo ci) {
        for (PhysicsBlockEntity be : PhysicsBlockEntity.clientLive()) {
            if (be.isRemoved() || be.getWorld() == null) continue;
            BlockPos cell = be.getPos();
            // Stale entries (unloaded chunk, replaced block) are not drawn.
            if (be.getWorld().getBlockEntity(cell) != be) continue;
            // Sodium drew this block entity if its cell's section was visible.
            if (isBoxVisible(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 1, cell.getZ() + 1)) continue;
            matrices.push();
            matrices.translate(cell.getX() - camX, cell.getY() - camY, cell.getZ() - camZ);
            dispatcher.render(be, tickDelta, matrices, bufferSource);
            matrices.pop();
        }
    }
}
