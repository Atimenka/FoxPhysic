package dev.foxphys.mixin;

import dev.foxphys.FoxPhysMod;
import dev.foxphys.Lab;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private World world;

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("TAIL"))
    private void foxphys$pushRigidBodies(CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) return;
        Explosion explosion = (Explosion) (Object) this;
        try {
            Lab lab = FoxPhysMod.manager().labFor(serverWorld);
            if (lab != null) lab.applyExplosion(explosion.getPosition(), explosion.getPower());
        } catch (RuntimeException | LinkageError e) {
            FoxPhysMod.LOGGER.warn("FoxPhys could not apply explosion impulses", e);
        }
    }
}
