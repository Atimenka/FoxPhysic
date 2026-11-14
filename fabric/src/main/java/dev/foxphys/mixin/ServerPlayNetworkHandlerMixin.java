package dev.foxphys.mixin;

import dev.foxphys.FoxPhysMod;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Left-click detection. Air swings send no vanilla event, so the arm-swing packet is the only
 * signal for hitting a body without a block or entity in front. The work is deferred to the
 * server thread: the packet handler may run on the network thread.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void foxphys$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (packet.getHand() != Hand.MAIN_HAND || this.player == null) return;
        ServerPlayerEntity swinging = this.player;
        // Vanilla hands the packet to the server thread and calls this method again there. Only the
        // server-thread call acts, otherwise one swing would launch a held body and also hit it.
        if (!swinging.getServer().isOnThread()) return;
        swinging.getServer().execute(() -> FoxPhysMod.interactions().onSwing(swinging));
    }
}
