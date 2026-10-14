package dev.foxphys.client;

import dev.foxphys.FoxPhysBlocks;
import dev.foxphys.net.FoxPhysNetwork;
import dev.foxphys.net.FoxPhysNetwork.FluidFrame;
import dev.foxphys.net.FoxPhysNetwork.SoftMeshes;
import dev.foxphys.item.GravityGunItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client-only registrations: the physics block renderer, soft bodies, SPH water, and their packets. Server code never loads this class. */
public final class FoxPhysClient implements ClientModInitializer {
    /** R: grid-align the body held by the gravity gun (or the body in the crosshair). */
    public static final KeyBinding ALIGN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.foxphys.align", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.foxphys"));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only in the world and only with the gravity gun in the main hand; other presses are ignored.
            while (ALIGN_KEY.wasPressed()) {
                if (client.player != null && client.currentScreen == null
                        && client.player.getMainHandStack().getItem() instanceof GravityGunItem) {
                    ClientPlayNetworking.send(new FoxPhysNetwork.AlignRequest());
                }
            }
        });
        BlockEntityRendererFactories.register(FoxPhysBlocks.PHYSICS_BLOCK_ENTITY, PhysicsBlockRenderer::new);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            SoftBodyRenderer.render(context);
            FluidRenderer.render(context);
        });
        ClientPlayNetworking.registerGlobalReceiver(SoftMeshes.ID,
                (payload, context) -> context.client().execute(() -> SoftBodyRenderer.receive(payload)));
        ClientPlayNetworking.registerGlobalReceiver(FluidFrame.ID,
                (payload, context) -> context.client().execute(() -> FluidRenderer.receive(payload)));
    }
}
