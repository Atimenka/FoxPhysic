package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/**
 * Smoke test for soft-body attachments and wind, run without a server.
 * Covers the quaternion maths of SoftAttachments and the Rust side of anchor, anchor_targets,
 * release and wind. The rigid-body side (Lab.bodyById, toWorld) needs a running server and is
 * not covered here.
 */
public final class AttachSmoke {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("ATTACH_SMOKE_FAIL: " + what);
        System.out.println("ok: " + what);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    public static void main(String[] args) {
        // 90 degrees about z: (1,0,0) -> (0,1,0); the inverse maps it back.
        double s = Math.sqrt(0.5);
        float[] q = {0f, 0f, (float) s, (float) s};
        double[] r = SoftAttachments.rotate(q, new double[] {1, 0, 0});
        check(near(r[0], 0, 1e-6) && near(r[1], 1, 1e-6) && near(r[2], 0, 1e-6), "rotate 90 deg about z");
        double[] back = SoftAttachments.rotateInverse(q, r);
        check(near(back[0], 1, 1e-6) && near(back[1], 0, 1e-6), "rotateInverse undoes rotate");
        // Identity and a non-unit quaternion (normalised before use).
        double[] id = SoftAttachments.rotate(new float[] {0, 0, 0, 1}, new double[] {0.3, -2, 5});
        check(near(id[0], 0.3, 1e-9) && near(id[1], -2, 1e-9) && near(id[2], 5, 1e-9), "identity keeps the vector");
        double[] scaled = SoftAttachments.rotate(new float[] {0, 0, 2f * (float) s, 2f * (float) s}, new double[] {1, 0, 0});
        check(near(scaled[1], 1, 1e-6), "non-unit quaternion is normalised");

        // Per-block table: every vanilla block is there, and the API override wins over it.
        check(BlockPhysics.size() == 1060, "block physics table has 1060 blocks: " + BlockPhysics.size());
        check(BlockPhysics.get("minecraft:stone").density() == 2650.0, "stone density from the table");
        check(BlockPhysics.get("minecraft:obsidian").blast() == 1200.0, "obsidian blast resistance from the game");
        DensityTable.register("minecraft:stone", 1234.0);
        check(DensityTable.overridden("minecraft:stone") == 1234.0, "API override is stored");
        DensityTable.unregister("minecraft:stone");

        // Rust side: a rope, its top layer anchored, moved 1 m in +x.
        JsonObject spawn = SoftBodies.spawn("rope", 0, 5, 0, 0).deepCopy();
        int id1 = spawn.get("id").getAsInt();
        JsonObject request = new JsonObject();
        request.addProperty("id", id1);
        request.addProperty("side", "top");
        JsonObject anchored = SoftBodies.call("anchor", request);
        JsonArray indices = anchored.getAsJsonArray("indices");
        JsonArray positions = anchored.getAsJsonArray("positions");
        check(indices.size() > 0 && positions.size() == 3 * indices.size(), "anchor returns indices and positions");
        int idx = indices.get(0).getAsInt();
        double x0 = positions.get(0).getAsDouble(), y0 = positions.get(1).getAsDouble(), z0 = positions.get(2).getAsDouble();

        for (int k = 1; k <= 20; k++) {
            JsonArray targets = new JsonArray();
            JsonArray t = new JsonArray();
            t.add(idx); t.add(x0 + k * 0.05); t.add(y0); t.add(z0);
            targets.add(t);
            JsonObject tr = new JsonObject();
            tr.addProperty("id", id1);
            tr.add("targets", targets);
            SoftBodies.call("anchor_targets", tr);
            SoftBodies.setWind(new Vec3(4, 0, 0));
            SoftBodies.tick();
        }
        JsonObject pos = SoftBodies.withId("positions", id1);
        double px = pos.getAsJsonArray("pos").get(3 * idx).getAsDouble();
        check(near(px, x0 + 1.0, 2e-3), "anchored particle followed its target: " + px);

        JsonObject status = SoftBodies.withId("status", id1);
        check(status.get("anchored").getAsInt() == indices.size(), "status reports the anchored count");
        check(status.get("finite").getAsBoolean(), "body stays finite under wind and anchoring");

        // A target for a particle that is not anchored is refused and nothing changes.
        JsonArray bad = new JsonArray();
        JsonArray bt = new JsonArray();
        bt.add(999999); bt.add(0); bt.add(0); bt.add(0);
        bad.add(bt);
        JsonObject badRequest = new JsonObject();
        badRequest.addProperty("id", id1);
        badRequest.add("targets", bad);
        boolean refused = false;
        try {
            SoftBodies.call("anchor_targets", badRequest);
        } catch (IllegalStateException e) {
            refused = true;
        }
        check(refused, "anchor_targets refuses a particle that is not anchored");

        SoftBodies.withId("release", id1);
        status = SoftBodies.withId("status", id1);
        check(status.get("anchored").getAsInt() == 0, "release frees every anchored particle");
        SoftBodies.remove(id1);
        System.out.println("ATTACH_SMOKE_OK");
    }
}
