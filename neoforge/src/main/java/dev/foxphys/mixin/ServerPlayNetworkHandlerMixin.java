package dev.foxphys.mixin;

import dev.foxphys.FoxPhysMod;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void foxphys$handleAnimate(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (packet.getHand() != InteractionHand.MAIN_HAND || this.player == null) return;
        ServerPlayer swinging = this.player;
        // Vanilla hands the packet to the server thread and calls this method again there. Only the
        // server-thread call acts, otherwise one swing would launch a held body and also hit it.
        if (!swinging.getServer().isSameThread()) return;
        swinging.getServer().execute(() -> FoxPhysMod.interactions().onSwing(swinging));
    }
}
