package dev.foxphys.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.foxphys.net.FoxPhysNetwork.FluidFrame;
import dev.foxphys.net.FoxPhysNetwork.FluidPoolFrame;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws SPH water pools. Each particle is a cube of the particle spacing, shaded by face and tinted by
 * height in the pool, so the water reads as a mass rather than as dots. Positions are interpolated between
 * the last two server ticks.
 *
 * This is a particle view, not a fluid surface: there is no surface reconstruction yet.
 */
public final class FluidRenderer {
    private FluidRenderer() {}

    private static final long TICK_NANOS = 50_000_000L;

    private record Frame(long receivedAt, Map<Integer, FluidPoolFrame> byId) {}

    private static volatile Frame previous;
    private static volatile Frame current;

    /** Called on the client thread with the water frame of the server tick. */
    public static void receive(FluidFrame payload) {
        Map<Integer, FluidPoolFrame> byId = new HashMap<>();
        for (FluidPoolFrame f : payload.pools()) byId.put(f.id(), f);
        previous = current;
        current = new Frame(System.nanoTime(), byId);
    }

    /** Stage hook: after the solid blocks, so the water sits in the world. */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Frame now = current;
        if (now == null || now.byId().isEmpty()) return;
        Frame before = previous;
        float alpha = (float) Math.min(1.0, Math.max(0.0, (System.nanoTime() - now.receivedAt()) / (double) TICK_NANOS));

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType type = RenderType.debugQuads();
        VertexConsumer consumer = buffers.getBuffer(type);
        for (FluidPoolFrame pool : now.byId().values()) {
            FluidPoolFrame old = before == null ? null : before.byId().get(pool.id());
            float[] pos = interpolate(pool, old, alpha);
            drawPool(consumer, matrix, pool.x(), pool.y(), pool.z(), pool.dx(), pos);
        }
        buffers.endBatch(type);
        poseStack.popPose();
    }

    private static float[] interpolate(FluidPoolFrame pool, FluidPoolFrame old, float alpha) {
        float[] pos = pool.xyz();
        if (old == null || old.xyz().length != pos.length || alpha >= 1f) return pos;
        float[] out = new float[pos.length];
        float[] prev = old.xyz();
        for (int i = 0; i < pos.length; i++) out[i] = prev[i] + (pos[i] - prev[i]) * alpha;
        return out;
    }

    private static void drawPool(VertexConsumer c, Matrix4f m, double ox, double oy, double oz, float dx, float[] p) {
        float top = 0f;
        for (int i = 1; i < p.length; i += 3) top = Math.max(top, p[i]);
        float h = Math.max(1e-3f, top);
        float half = dx / 2f;
        for (int i = 0; i + 2 < p.length; i += 3) {
            float x = (float) ox + p[i], y = (float) oy + p[i + 1], z = (float) oz + p[i + 2];
            // Tint: lighter near the surface.
            float depth = Math.min(1f, Math.max(0f, p[i + 1] / h));
            float r = 0.10f + 0.20f * depth, g = 0.38f + 0.22f * depth, b = 0.80f + 0.15f * depth;
            cube(c, m, x, y, z, half, r, g, b);
        }
    }

    /** Axis-aligned cube centred at (cx, cy, cz) with half-size {@code s}; each face has its own shade. */
    private static void cube(VertexConsumer c, Matrix4f m, float cx, float cy, float cz, float s, float r, float g, float b) {
        float x0 = cx - s, x1 = cx + s, y0 = cy - s, y1 = cy + s, z0 = cz - s, z1 = cz + s;
        // Top (lit), bottom (dark), then the four sides.
        face(c, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, 1.00f);
        face(c, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, r, g, b, 0.45f);
        face(c, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, 0.80f);
        face(c, m, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, r, g, b, 0.80f);
        face(c, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, 0.65f);
        face(c, m, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, 0.65f);
    }

    private static void face(VertexConsumer c, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float shade) {
        // Both windings: the cube is visible from inside and outside, whatever the culling state.
        vertex(c, m, ax, ay, az, r * shade, g * shade, b * shade);
        vertex(c, m, bx, by, bz, r * shade, g * shade, b * shade);
        vertex(c, m, cx, cy, cz, r * shade, g * shade, b * shade);
        vertex(c, m, dx, dy, dz, r * shade, g * shade, b * shade);
        vertex(c, m, dx, dy, dz, r * shade, g * shade, b * shade);
        vertex(c, m, cx, cy, cz, r * shade, g * shade, b * shade);
        vertex(c, m, bx, by, bz, r * shade, g * shade, b * shade);
        vertex(c, m, ax, ay, az, r * shade, g * shade, b * shade);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, float r, float g, float b) {
        c.addVertex(m, x, y, z).setColor(r, g, b, 1f);
    }
}
