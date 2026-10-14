package dev.foxphys.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.foxphys.net.FoxPhysNetwork.SoftMesh;
import dev.foxphys.net.FoxPhysNetwork.SoftMeshes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws soft bodies: the surface triangles from the server, shaded, one colour per preset.
 *
 * The server sends the shape once per tick (50 ms). Positions are interpolated between the last two
 * shapes, so the motion is smooth at any frame rate. Culling is off for this render type, so the
 * surface shows from either side without relying on the winding order.
 */
public final class SoftBodyRenderer {
    private SoftBodyRenderer() {}

    private static final long TICK_NANOS = 50_000_000L;

    private record Frame(long receivedAt, Map<Integer, SoftMesh> byId) {}

    private static volatile Frame previous;
    private static volatile Frame current;

    private static int reportedCount = -1;

    /** Surface triangles by body id, from the last full send (the server sends them in full now and then). */
    private static Map<Integer, int[]> surfaces = Map.of();

    /** Called on the client thread with a new shape update from the server. */
    public static void receive(SoftMeshes payload) {
        // A mesh with an empty triangle list keeps the surface received earlier for the same body.
        Map<Integer, int[]> next = new HashMap<>();
        List<SoftMesh> meshes = new ArrayList<>(payload.meshes().size());
        for (SoftMesh mesh : payload.meshes()) {
            int[] tris = mesh.triangles();
            if (tris.length > 0) {
                next.put(mesh.id(), tris);
            } else if (surfaces.containsKey(mesh.id())) {
                tris = surfaces.get(mesh.id());
                next.put(mesh.id(), tris);
            }
            meshes.add(new SoftMesh(mesh.id(), mesh.preset(), mesh.positions(), tris));
        }
        surfaces = next;
        Map<Integer, SoftMesh> byId = new HashMap<>();
        for (SoftMesh mesh : meshes) byId.put(mesh.id(), mesh);
        // Diagnostics for "soft bodies not visible": log when the number of bodies changes.
        if (meshes.size() != reportedCount) {
            reportedCount = meshes.size();
            if (reportedCount == 0) {
                dev.foxphys.FoxPhysMod.LOGGER.info("FoxPhys soft bodies: none");
            } else {
                for (SoftMesh mesh : meshes) {
                    float[] p = mesh.positions();
                    float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
                    float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                    for (int i = 0; i + 2 < p.length; i += 3) {
                        for (int k = 0; k < 3; k++) {
                            lo[k] = Math.min(lo[k], p[i + k]);
                            hi[k] = Math.max(hi[k], p[i + k]);
                        }
                    }
                    dev.foxphys.FoxPhysMod.LOGGER.info(
                            "FoxPhys soft body #{} ({}): {} particles, {} triangle indices, box x {}..{} y {}..{} z {}..{}",
                            mesh.id(), mesh.preset(), p.length / 3, mesh.triangles().length,
                            lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
                }
            }
        }
        previous = current;
        current = new Frame(System.nanoTime(), byId);
    }

    /** Stage hook: draws after the solid blocks so the soft bodies sit in the world. */
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
        for (SoftMesh mesh : now.byId().values()) {
            SoftMesh old = before == null ? null : before.byId().get(mesh.id());
            float[] pos = interpolate(mesh, old, alpha);
            int[] tris = mesh.triangles();
            float[] rgb = colorOf(mesh.preset());
            for (int t = 0; t + 2 < tris.length; t += 3) {
                drawTriangle(consumer, matrix, pos, tris[t], tris[t + 1], tris[t + 2], rgb);
            }
        }
        buffers.endBatch(type);
        poseStack.popPose();
    }

    private static float[] interpolate(SoftMesh mesh, SoftMesh old, float alpha) {
        float[] pos = mesh.positions();
        if (old == null || old.positions().length != pos.length || alpha >= 1f) return pos;
        float[] out = new float[pos.length];
        float[] prev = old.positions();
        for (int i = 0; i < pos.length; i++) out[i] = prev[i] + (pos[i] - prev[i]) * alpha;
        return out;
    }

    private static void drawTriangle(VertexConsumer c, Matrix4f m, float[] p, int a, int b, int d, float[] rgb) {
        float ax = p[3 * a], ay = p[3 * a + 1], az = p[3 * a + 2];
        float bx = p[3 * b], by = p[3 * b + 1], bz = p[3 * b + 2];
        float cx = p[3 * d], cy = p[3 * d + 1], cz = p[3 * d + 2];
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9f) return;
        nx /= len; ny /= len; nz /= len;
        // Light from above and a little from the side; the same value on both faces.
        float lit = Math.min(1f, 0.5f + 0.35f * Math.abs(ny) + 0.15f * Math.abs(nx));
        float r = rgb[0] * lit, g = rgb[1] * lit, bl = rgb[2] * lit;
        // Quad with a repeated last vertex = triangle. The debug quad type disables culling, so one
        // winding is drawn from both sides; a second copy would only double the vertices.
        quad(c, m, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, bl);
    }

    private static void quad(VertexConsumer c, Matrix4f m, float ax, float ay, float az,
                             float bx, float by, float bz, float cx, float cy, float cz,
                             float r, float g, float b) {
        vertex(c, m, ax, ay, az, r, g, b);
        vertex(c, m, bx, by, bz, r, g, b);
        vertex(c, m, cx, cy, cz, r, g, b);
        vertex(c, m, cx, cy, cz, r, g, b);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, float r, float g, float b) {
        c.addVertex(m, x, y, z).setColor(r, g, b, 1f);
    }

    /** Preset colours (rubber, jelly, cloth, rope); unknown presets are grey. */
    static float[] colorOf(String preset) {
        return switch (preset) {
            case "rubber" -> new float[] {0.85f, 0.28f, 0.23f};
            case "jelly" -> new float[] {0.37f, 0.82f, 0.48f};
            case "cloth" -> new float[] {0.91f, 0.89f, 0.82f};
            case "rope" -> new float[] {0.85f, 0.64f, 0.25f};
            default -> new float[] {0.75f, 0.77f, 0.80f};
        };
    }
}
