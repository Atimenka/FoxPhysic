package dev.foxphys.mixin.client;

import dev.foxphys.Lab;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Presentation only. Lab bodies are drawn as block displays. The vanilla dispatcher also tests a display against
 * the view frustum, using the display's culling box, and that box is refreshed only when the display's transform
 * is refreshed. For a body that moves fast this test can hide a visible body. Lab displays therefore skip the
 * frustum test and keep the distance test, so far-away displays still cost nothing. Other entities keep the
 * vanilla decision. require=0: if the method differs in another build, vanilla culling applies.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void foxphys$keepLabBodiesInFrustum(Entity entity, Frustum frustum, double x, double y, double z,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Display && entity.getTags().contains(Lab.TAG)) {
            cir.setReturnValue(entity.shouldRender(x, y, z));
        }
    }
}
