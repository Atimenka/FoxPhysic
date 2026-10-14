package dev.foxphys.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.foxphys.FoxPhysBlocks;
import dev.foxphys.item.GravityGunItem;
import dev.foxphys.net.FoxPhysNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Client-only registrations: the physics block renderer, soft bodies, and the align key. */
public final class FoxPhysClient {
    private FoxPhysClient() {}

    /** R: grid-align the body held by the gravity gun (or the body in the crosshair). */
    public static final KeyMapping ALIGN_KEY = new KeyMapping(
            "key.foxphys.align", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.foxphys");

    public static void init(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e ->
                e.registerBlockEntityRenderer(FoxPhysBlocks.PHYSICS_BLOCK_ENTITY.get(), PhysicsBlockRenderer::new));
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(ALIGN_KEY));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> onClientTick());
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, SoftBodyRenderer::render);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, dev.foxphys.client.FluidRenderer::render);
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        while (ALIGN_KEY.consumeClick()) {
            // Only in the world and only with the gravity gun in the main hand; other presses are ignored.
            if (mc.player != null && mc.screen == null && mc.player.getMainHandItem().getItem() instanceof GravityGunItem) {
                PacketDistributor.sendToServer(new FoxPhysNetwork.AlignRequest());
            }
        }
    }
}
