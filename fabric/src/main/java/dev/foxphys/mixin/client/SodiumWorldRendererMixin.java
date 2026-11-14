package dev.foxphys.mixin.client;

import dev.foxphys.Lab;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Presentation only. Sodium's entity culling may hide a body's block displays when their
 * section looks occluded although the body moves in the open. Lab displays are always drawn;
 * every other entity keeps Sodium's decision. Sodium is not a compile dependency, so the
 * target is a string; {@link FoxPhysMixinPlugin} applies this only when Sodium is loaded.
 * require=0: a Sodium whose method differs is skipped and vanilla visibility applies.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererMixin {
    @Inject(method = "isEntityVisible(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxphys$keepLabDisplaysVisible(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof DisplayEntity && entity.getCommandTags().contains(Lab.TAG)) {
            cir.setReturnValue(true);
        }
    }
}
