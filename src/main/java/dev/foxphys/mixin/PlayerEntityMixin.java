package dev.foxphys.mixin;

import dev.foxphys.FoxPhysMod;
import dev.foxphys.Lab;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the newest body contact (carry, push-out, standing, sneak hold) at the start of a
 * server player's tick, before vanilla movement. The same contact is applied once: when this has
 * already run, the end-of-world-tick path in {@link Lab} skips it. If this mixin does not apply,
 * that end-of-tick path still applies every contact, so the player still follows bodies.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    private static volatile boolean foxphys$warned;

    @Inject(method = "tick", at = @At("HEAD"))
    private void foxphys$applyBodyContacts(CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!(self instanceof ServerPlayerEntity) || !(self.getWorld() instanceof ServerWorld world)) return;
        try {
            Lab lab = FoxPhysMod.manager().labFor(world);
            if (lab != null) lab.applyLatestContacts();
        } catch (RuntimeException e) {
            // Never break the player's tick: the end-of-tick path keeps the contacts working.
            if (!foxphys$warned) {
                foxphys$warned = true;
                FoxPhysMod.LOGGER.warn("FoxPhys: early contact application failed; using end-of-tick contacts", e);
            }
        }
    }
}
