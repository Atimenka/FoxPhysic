package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.foxphys.Protocol.BodyState;
import dev.foxphys.Protocol.Part;
import dev.foxphys.Protocol.Snapshot;
import dev.foxphys.item.GravityGunItem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import static dev.foxphys.Protocol.*;

/**
 * Player interaction with physics bodies: hand hits knock bodies back, the gravity gun holds and
 * launches them. Hit tests are exact oriented-box rays in the body frame. The physics itself stays
 * in Rust: this class only sends impulses and grab targets.
 */
public final class Interactions {
    /** Reach of a bare hand, metres. */
    static final double HAND_REACH = 4.5;
    /** Gravity gun reach for grabbing, metres. */
    static final double GUN_REACH = 16.0;
    /** A grabbed body is released beyond this distance from the holder. */
    static final double RELEASE_DISTANCE = 24.0;
    /** Velocity change of a body from one hand hit, m/s. */
    static final double HIT_DELTA_V = 1.5;
    /** Velocity change of a body launched by the gravity gun, m/s. */
    static final double LAUNCH_DELTA_V = 20.0;
    /** Ticks between two hand hits of one player. */
    static final long HIT_COOLDOWN_TICKS = 8;
    /** Hold gains: frequency (rad/s) and damping ratio; force is capped at max_accel. */
    static final double GRAB_FREQUENCY = 6.0, GRAB_DAMPING = 0.7, GRAB_MAX_ACCEL = 60.0;

    /** One grabbed body held by one player. `point` is in the body frame. */
    private record Hold(ServerPlayerEntity player, long bodyId, float[] point, double distance) {}

    private final LabManager manager;
    private final Map<UUID, Hold> holds = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();

    public Interactions(LabManager manager) {
        this.manager = manager;
    }

    /** Registers the right-click handler (gravity gun grab and release). */
    public void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            if (hand != Hand.MAIN_HAND || !(sp.getMainHandStack().getItem() instanceof GravityGunItem)) return TypedActionResult.pass(sp.getStackInHand(hand));
            onUse(sp);
            return TypedActionResult.success(sp.getStackInHand(hand), false);
        });
    }

    /** Called on the server thread for a main-hand arm swing. */
    public void onSwing(ServerPlayerEntity player) {
        Lab lab = manager.labFor((ServerWorld) player.getWorld());
        if (lab == null) return;
        Hold hold = holds.get(player.getUuid());
        if (hold != null) {
            launch(lab, player, hold);
            return;
        }
        long now = player.getWorld().getTime();
        Long last = lastHit.get(player.getUuid());
        if (last != null && now - last < HIT_COOLDOWN_TICKS) return;
        Vec3d eye = player.getEyePos(), look = player.getRotationVec(1f);
        // A block in front of the body stops the hit: the vanilla block raycast decides first.
        double limit = HAND_REACH;
        HitResult block = player.raycast(HAND_REACH, 0f, false);
        if (block.getType() != HitResult.Type.MISS) limit = Math.min(limit, eye.distanceTo(block.getPos()));
        Pick pick = pick(lab, eye, look, limit);
        if (pick == null) return;
        lastHit.put(player.getUuid(), now);
        Vec3d dir = look.normalize();
        try {
            lab.impulse(pick.body.id(), dir.multiply(pick.body.mass() * HIT_DELTA_V), pick.worldPoint);
            player.sendMessage(Text.literal(String.format(java.util.Locale.ROOT, "Удар: тело #%d, масса %.0f кг", pick.body.id(), pick.body.mass())), true);
        } catch (RuntimeException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
    }

    private void onUse(ServerPlayerEntity player) {
        Lab lab = manager.labFor((ServerWorld) player.getWorld());
        if (lab == null) {
            player.sendMessage(Text.literal("Физических тел нет: соберите блоки сборщиком или мёд-клеем"), true);
            return;
        }
        if (holds.remove(player.getUuid()) != null) {
            player.sendMessage(Text.literal("Гравипушка: отпущено"), true);
            return;
        }
        Vec3d eye = player.getEyePos(), look = player.getRotationVec(1f);
        Pick pick = pick(lab, eye, look, GUN_REACH);
        if (pick == null) {
            player.sendMessage(Text.literal("Гравипушка: тело не в прицеле"), true);
            return;
        }
        if (holds.size() >= 4) {
            player.sendMessage(Text.literal("Гравипушка: слишком много захватов на сервере"), true);
            return;
        }
        // Body-frame grab point: the physics pulls this point, not the centre of mass.
        Vector3f local = new Vector3f(
                (float) (pick.localPoint.x - pick.body.position()[0]),
                (float) (pick.localPoint.y - pick.body.position()[1]),
                (float) (pick.localPoint.z - pick.body.position()[2]));
        new Quaternionf(pick.body.rotation()[0], pick.body.rotation()[1], pick.body.rotation()[2], pick.body.rotation()[3]).conjugate().transform(local);
        holds.put(player.getUuid(), new Hold(player, pick.body.id(), new float[]{local.x, local.y, local.z}, pick.distance));
        player.sendMessage(Text.literal("Гравипушка: тело #" + pick.body.id() + " захвачено. ЛКМ — запуск, ПКМ — отпустить"), true);
    }

    private void launch(Lab lab, ServerPlayerEntity player, Hold hold) {
        holds.remove(player.getUuid());
        BodyState body = lab.bodyById(hold.bodyId());
        if (body == null) return;
        Vec3d dir = player.getRotationVec(1f).normalize();
        try {
            lab.impulse(body.id(), dir.multiply(body.mass() * LAUNCH_DELTA_V), lab.toWorld(new Vec3d(body.position()[0], body.position()[1], body.position()[2])));
            player.sendMessage(Text.literal("Гравипушка: запуск тела #" + body.id()), true);
        } catch (RuntimeException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
    }

    /** Grab targets for the lab's next push. Releases holds whose player, item or body is gone. */
    public JsonArray grabsFor(Lab lab) {
        JsonArray list = new JsonArray();
        Iterator<Map.Entry<UUID, Hold>> it = holds.entrySet().iterator();
        while (it.hasNext()) {
            Hold h = it.next().getValue();
            ServerPlayerEntity p = h.player();
            boolean holding = !p.isRemoved() && p.getWorld() == lab.world() && p.getMainHandStack().getItem() instanceof GravityGunItem;
            BodyState body = holding ? lab.bodyById(h.bodyId()) : null;
            Vec3d eye = holding ? p.getEyePos() : null;
            if (body == null || eye == null || eye.distanceTo(lab.toWorld(new Vec3d(body.position()[0], body.position()[1], body.position()[2]))) > RELEASE_DISTANCE) {
                it.remove();
                continue;
            }
            Vec3d target = lab.toLocal(eye.add(p.getRotationVec(1f).multiply(h.distance())));
            JsonObject grab = new JsonObject();
            grab.addProperty("id", h.bodyId());
            grab.add("point", vector(h.point()[0], h.point()[1], h.point()[2]));
            grab.add("target", vector(target.x, target.y, target.z));
            grab.addProperty("frequency", GRAB_FREQUENCY);
            grab.addProperty("damping_ratio", GRAB_DAMPING);
            grab.addProperty("max_accel", GRAB_MAX_ACCEL);
            list.add(grab);
        }
        return list;
    }

    /** Drops every hold (lab closed, server stopping). */
    public void releaseAll() {
        holds.clear();
        lastHit.clear();
    }

    /** Body hit by a ray: world point of the hit, distance from the ray origin, and the body. */
    /**
     * R in the gravity gun: snaps the held body to the block grid (or the body in the crosshair when
     * nothing is held). The client only sends the key press; the pose is set here on the server.
     */
    public void align(ServerPlayerEntity player) {
        if (!(player.getMainHandStack().getItem() instanceof GravityGunItem)) return;
        Lab lab = manager.labFor((ServerWorld) player.getWorld());
        if (lab == null) {
            player.sendMessage(Text.literal("Физических тел нет: соберите блоки сборщиком или мёд-клеем"), true);
            return;
        }
        long id;
        Hold hold = holds.get(player.getUuid());
        if (hold != null) {
            id = hold.bodyId();
        } else {
            Pick pick = pick(lab, player.getEyePos(), player.getRotationVec(1f), GUN_REACH);
            if (pick == null) {
                player.sendMessage(Text.literal("Гравипушка: тело не в прицеле"), true);
                return;
            }
            id = pick.body.id();
        }
        BodyState body = lab.bodyById(id);
        if (body == null) return;
        try {
            GridAlign.Pose pose = GridAlign.align(body.position(), body.rotation(), partOffsets(body), partSizes(body));
            lab.setPose(id, pose.position(), pose.rotation());
            player.sendMessage(Text.literal("Гравипушка: тело #" + id + " выровнено по сетке"), true);
        } catch (RuntimeException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
    }

    private static float[][] partOffsets(BodyState body) {
        if (body.parts() == null) return new float[0][];
        float[][] out = new float[body.parts().size()][];
        for (int i = 0; i < out.length; i++) out[i] = body.parts().get(i).offset();
        return out;
    }

    private static float[][] partSizes(BodyState body) {
        if (body.parts() == null) return new float[0][];
        float[][] out = new float[body.parts().size()][];
        for (int i = 0; i < out.length; i++) out[i] = body.parts().get(i).size();
        return out;
    }

    record Pick(BodyState body, Vec3d localPoint, Vec3d worldPoint, double distance) {}

    /** Nearest body whose oriented box the ray hits within maxDistance (world coordinates). */
    static Pick pick(Lab lab, Vec3d eyeWorld, Vec3d dirWorld, double maxDistance) {
        Snapshot snap = lab.snapshot();
        if (snap == null) return null;
        Vec3d o = lab.toLocal(eyeWorld), d = dirWorld.normalize();
        Pick best = null;
        for (BodyState b : snap.bodies()) {
            double t = rayHit(b, o, d, maxDistance);
            if (Double.isNaN(t)) continue;
            if (best == null || t < best.distance()) {
                Vec3d local = o.add(d.multiply(t));
                best = new Pick(b, local, lab.toWorld(local), t);
            }
        }
        return best;
    }

    /** Ray against all parts of one body. Returns the entry distance, or NaN on a miss. */
    static double rayHit(BodyState body, Vec3d o, Vec3d d, double maxDistance) {
        if (body.parts() == null || body.parts().isEmpty()) return Double.NaN;
        float[] r = body.rotation();
        Quaternionf inverse = new Quaternionf(r[0], r[1], r[2], r[3]).conjugate();
        Vector3f lo = inverse.transform(new Vector3f((float) (o.x - body.position()[0]), (float) (o.y - body.position()[1]), (float) (o.z - body.position()[2])));
        Vector3f ld = inverse.transform(new Vector3f((float) d.x, (float) d.y, (float) d.z));
        double best = Double.NaN;
        for (Part p : body.parts()) {
            double t = slab(lo, ld, p, maxDistance);
            if (!Double.isNaN(t) && (Double.isNaN(best) || t < best)) best = t;
        }
        return best;
    }

    private static double slab(Vector3f lo, Vector3f ld, Part p, double maxDistance) {
        double tmin = 0, tmax = maxDistance;
        float[] min = new float[3], max = new float[3];
        for (int i = 0; i < 3; i++) {
            min[i] = (float) (p.offset()[i] - p.size()[i] / 2);
            max[i] = (float) (p.offset()[i] + p.size()[i] / 2);
        }
        float[] origin = {lo.x, lo.y, lo.z}, dir = {ld.x, ld.y, ld.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-9f) {
                if (origin[i] < min[i] || origin[i] > max[i]) return Double.NaN;
                continue;
            }
            double t1 = (min[i] - origin[i]) / dir[i], t2 = (max[i] - origin[i]) / dir[i];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.NaN;
        }
        return tmin;
    }
}
