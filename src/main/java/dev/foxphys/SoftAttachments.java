package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.Vec3d;

/**
 * Fastens a layer of a soft body (a flag, a sail, a rope end) to a rigid body.
 *
 * Each anchored particle keeps its offset in the rigid body's frame. Every server tick, before the
 * soft bodies step, its target is the rigid body's current pose applied to that offset. Rust holds
 * the particles fixed at those targets, so the soft body follows the rigid body. The rigid body
 * does not feel the soft body: the coupling is one way.
 *
 * Only the server thread touches this class.
 */
public final class SoftAttachments {
    private SoftAttachments() {}

    /** Offsets are in the rigid body's frame (lab-local, rotated by the body's inverse rotation). */
    private record Attachment(int softId, Lab lab, long bodyId, int[] indices, double[][] offsets) {}

    private static final Map<Integer, Attachment> ATTACHED = new HashMap<>();

    /** Side of the soft body that is fastened: top/bottom (by y) or start/end (by x). */
    public static final String[] SIDES = {"top", "bottom", "start", "end"};

    /** Search radius (m) for the rigid body nearest to the fastened layer. */
    static final double NEAREST_RADIUS = 3.0;

    /**
     * Fastens one layer of soft body {@code softId} to a rigid body. With {@code bodyId == null} the
     * nearest rigid body of the lab to the layer's centre is used (within {@link #NEAREST_RADIUS}).
     * Returns a message for the player. Throws IllegalArgumentException for bad input and
     * IllegalStateException when the native side refuses (for example, an old native library).
     */
    public static String attach(Lab lab, int softId, String side, Long bodyId) {
        if (!java.util.Arrays.asList(SIDES).contains(side)) {
            throw new IllegalArgumentException("Сторона: top, bottom, start или end.");
        }
        detach(softId);
        JsonObject request = new JsonObject();
        request.addProperty("id", softId);
        request.addProperty("side", side);
        JsonObject anchored = SoftBodies.call("anchor", request);
        JsonArray indexArray = anchored.getAsJsonArray("indices");
        int n = indexArray.size();
        if (n == 0) throw new IllegalArgumentException("Слой пуст: у тела нет частиц с этой стороны.");
        int[] indices = new int[n];
        double[][] worldPoints = new double[n][3];
        JsonArray positions = anchored.getAsJsonArray("positions");
        double cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < n; k++) {
            indices[k] = indexArray.get(k).getAsInt();
            for (int c = 0; c < 3; c++) worldPoints[k][c] = positions.get(3 * k + c).getAsDouble();
            cx += worldPoints[k][0] / n; cy += worldPoints[k][1] / n; cz += worldPoints[k][2] / n;
        }
        try {
            var body = bodyId == null
                    ? lab.bodyNear(new Vec3d(cx, cy, cz), NEAREST_RADIUS)
                    : lab.bodyById(bodyId);
            if (body == null) {
                throw new IllegalArgumentException(bodyId == null
                        ? "Рядом со слоем нет физического тела (радиус " + NEAREST_RADIUS + " м)."
                        : "Нет физического тела с id " + bodyId + ".");
            }
            double[][] offsets = new double[n][3];
            for (int k = 0; k < n; k++) {
                Vec3d local = lab.toLocal(new Vec3d(worldPoints[k][0], worldPoints[k][1], worldPoints[k][2]));
                double[] rel = {
                    local.x - body.position()[0], local.y - body.position()[1], local.z - body.position()[2]};
                double[] off = rotateInverse(body.rotation(), rel);
                offsets[k] = off;
            }
            ATTACHED.put(softId, new Attachment(softId, lab, body.id(), indices, offsets));
            return "Слой (" + side + ", частиц " + n + ") закреплён к телу #" + body.id() + ".";
        } catch (RuntimeException e) {
            releaseParticles(softId);
            throw e;
        }
    }

    /** Frees the fastened layer of one soft body, if any. */
    public static boolean detach(int softId) {
        Attachment a = ATTACHED.remove(softId);
        if (a == null) return false;
        releaseParticles(softId);
        return true;
    }

    private static void releaseParticles(int softId) {
        JsonObject request = new JsonObject();
        request.addProperty("id", softId);
        try {
            SoftBodies.call("release", request);
        } catch (RuntimeException e) {
            FoxPhysMod.LOGGER.warn("FoxPhys soft release {} failed: {}", softId, e.getMessage());
        }
    }

    /** Called when a soft body is removed: forget its attachment without calling Rust. */
    static void forget(int softId) {
        ATTACHED.remove(softId);
    }

    /** Called when a lab closes: its attachments end (the rigid bodies are gone). */
    static void releaseLab(Lab lab) {
        Iterator<Map.Entry<Integer, Attachment>> it = ATTACHED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Attachment> e = it.next();
            if (e.getValue().lab() == lab) {
                it.remove();
                releaseParticles(e.getKey());
            }
        }
    }

    /** Called when all soft bodies are cleared. */
    static void clear() {
        ATTACHED.clear();
    }

    /** Server tick, before the soft bodies step: sets every fastened particle to its target. */
    public static void tick() {
        if (ATTACHED.isEmpty()) return;
        Iterator<Map.Entry<Integer, Attachment>> it = ATTACHED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Attachment> entry = it.next();
            Attachment a = entry.getValue();
            Protocol.BodyState body = a.lab().bodyById(a.bodyId());
            if (body == null || a.lab().failed()) {
                // The rigid body is gone (broke apart, was disassembled): the layer is free again.
                it.remove();
                releaseParticles(a.softId());
                continue;
            }
            try {
                JsonArray targets = new JsonArray();
                for (int k = 0; k < a.indices().length; k++) {
                    double[] world = rotate(body.rotation(), a.offsets()[k]);
                    Vec3d lab = a.lab().toWorld(new Vec3d(
                            body.position()[0] + world[0], body.position()[1] + world[1], body.position()[2] + world[2]));
                    JsonArray t = new JsonArray();
                    t.add(a.indices()[k]);
                    t.add(lab.x); t.add(lab.y); t.add(lab.z);
                    targets.add(t);
                }
                JsonObject request = new JsonObject();
                request.addProperty("id", a.softId());
                request.add("targets", targets);
                SoftBodies.call("anchor_targets", request);
            } catch (RuntimeException e) {
                it.remove();
                FoxPhysMod.LOGGER.warn("FoxPhys attachment of soft body {} stopped: {}", a.softId(), e.getMessage());
                releaseParticles(a.softId());
            }
        }
    }

    /** Number of fastened soft bodies (for status messages). */
    public static int count() {
        return ATTACHED.size();
    }

    /** Rotates v by the unit quaternion q = (x, y, z, w). */
    static double[] rotate(float[] q, double[] v) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        double x = q[0] / n, y = q[1] / n, z = q[2] / n, w = q[3] / n;
        double tx = 2 * (y * v[2] - z * v[1]);
        double ty = 2 * (z * v[0] - x * v[2]);
        double tz = 2 * (x * v[1] - y * v[0]);
        return new double[] {
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx)
        };
    }

    /** Rotates v by the inverse of q (the conjugate, for a unit quaternion). */
    static double[] rotateInverse(float[] q, double[] v) {
        return rotate(new float[] {-q[0], -q[1], -q[2], q[3]}, v);
    }
}
