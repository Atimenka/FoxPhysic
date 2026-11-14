package dev.foxphys.api.client;

import dev.foxphys.block.PhysicsBlockEntity;
import dev.foxphys.client.FluidRenderer;
import dev.foxphys.client.SoftBodyRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Client-only read API for interpolated server-simulated geometry. Call from a client/render callback;
 * every returned list and array is a defensive snapshot. Do not reference this class on a dedicated server.
 */
public final class ClientPhysicsApi {
    private ClientPhysicsApi() {}

    /** Interpolated surface meshes at {@code tickDelta} in [0,1]. */
    public static List<SoftBodyFrame> softBodies(float tickDelta) {
        requireClientThread();
        return SoftBodyRenderer.frames(alpha(tickDelta));
    }

    /** Interpolated SPH particles at {@code tickDelta}; coordinates are relative to each pool's anchor. */
    public static List<FluidPoolFrame> fluidPools(float tickDelta) {
        requireClientThread();
        return FluidRenderer.frames(alpha(tickDelta));
    }

    /** Interpolated visible block cells for rigid bodies, with original states and body ids. */
    public static List<PhysicsBlockFrame> physicsBlocks(float tickDelta) {
        requireClientThread();
        var client = Minecraft.getInstance();
        if (client.level == null) return List.of();
        float t = alpha(tickDelta);
        List<PhysicsBlockFrame> result = new ArrayList<>();
        for (PhysicsBlockEntity entity : PhysicsBlockEntity.clientLive()) {
            Level level = entity.getLevel();
            if (level != client.level || level.getBlockEntity(entity.getBlockPos()) != entity) continue;
            result.add(new PhysicsBlockFrame(entity.getBlockPos(), entity.bodyId(), entity.originalState(),
                    entity.centerAt(t), entity.rotationAt(t)));
        }
        return List.copyOf(result);
    }

    private static float alpha(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 1f;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("FoxPhys client API must be called on the Minecraft client thread");
        }
    }
}
