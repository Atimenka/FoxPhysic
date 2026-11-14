package dev.foxphys.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.foxphys.SoftBodies;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Soft bodies: rubber, jelly, cloth and rope, simulated natively with XPBD. Bodies spawned with a
 * {@link ServerWorld} collide with nearby Minecraft block shapes and FoxPhys rigid bodies; all bodies
 * also use their optional floor plane and self-collision. Cloth and rope can tear under overload.
 * Limits: {@link #MAX_BODIES} bodies, {@link #MAX_PARTICLES} particles per body. Server thread only.
 */
public final class SoftBodyApi {
    public static final int MAX_BODIES = 16;
    public static final int MAX_PARTICLES = 5000;
    /** Preset names accepted by {@link #spawn}. */
    public static final List<String> PRESETS = List.of("rubber", "jelly", "cloth", "rope");

    SoftBodyApi() {}

    /**
     * Compatibility entry point. The body has no dimension owner, so it receives self-collision and
     * the optional floor plane but no Minecraft block or rigid-body colliders.
     */
    public int spawn(String preset, Vec3d origin, double floorY) {
        return spawnInternal(null, preset, origin, floorY);
    }

    /** Creates a world-bound soft body with block-shape and rigid-body collision sampling. */
    public int spawn(ServerWorld world, String preset, Vec3d origin, double floorY) {
        if (world == null) throw new IllegalArgumentException("world must not be null");
        return spawnInternal(world, preset, origin, floorY);
    }

    private static int spawnInternal(ServerWorld world, String preset, Vec3d origin, double floorY) {
        if (preset == null || !PRESETS.contains(preset)) throw new IllegalArgumentException("preset must be one of " + PRESETS);
        if (origin == null || !Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) {
            throw new IllegalArgumentException("origin must be a finite vector");
        }
        if (!Double.isFinite(floorY)) throw new IllegalArgumentException("floorY must be finite");
        return SoftBodies.spawn(world, preset, origin.x, origin.y, origin.z, floorY).get("id").getAsInt();
    }

    /** Ids of all soft bodies. */
    public List<Integer> ids() {
        JsonArray array = SoftBodies.call("list", new JsonObject()).getAsJsonArray("bodies");
        List<Integer> out = new ArrayList<>(array.size());
        for (var e : array) out.add(e.getAsInt());
        return out;
    }

    /** Number of soft bodies. */
    public int count() {
        return ids().size();
    }

    /** Status of one soft body, or empty when the id is unknown. */
    public Optional<SoftBodyInfo> info(int id) {
        if (!ids().contains(id)) return Optional.empty();
        JsonObject o = SoftBodies.withId("status", id);
        JsonArray c = o.getAsJsonArray("center");
        return Optional.of(new SoftBodyInfo(
                o.get("id").getAsInt(), o.get("preset").getAsString(), o.get("particles").getAsInt(),
                new Vec3d(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()),
                o.get("vmax").getAsDouble(), o.get("kinetic_energy").getAsDouble(),
                o.get("volume_ratio_min").getAsDouble(), o.get("volume_ratio_max").getAsDouble(),
                o.get("stiffness").getAsDouble(), o.get("damping").getAsDouble(), o.get("finite").getAsBoolean()));
    }

    /**
     * Removes a soft body and fires {@link dev.foxphys.api.event.SoftBodyRemovedEvent}.
     *
     * @throws IllegalStateException if the id is unknown
     */
    public void remove(int id) {
        SoftBodies.remove(id);
    }

    /**
     * Pins particles on one side of the body: {@code top}, {@code bottom}, {@code start} or {@code end}.
     * Pinned particles do not move. Returns the number of particles pinned.
     */
    public int pin(int id, String side) {
        if (side == null || !List.of("top", "bottom", "start", "end").contains(side)) {
            throw new IllegalArgumentException("side must be top, bottom, start or end");
        }
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("side", side);
        return SoftBodies.call("pin", a).get("pinned").getAsInt();
    }

    /** Pins one particle by index. Returns the number pinned (1). */
    public int pinParticle(int id, int index) {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("index", index);
        return SoftBodies.call("pin", a).get("pinned").getAsInt();
    }

    /** Multiplier on the preset stiffness, 0.05 to 100 (1.0 is the preset). Returns the value set. */
    public double setStiffness(int id, float value) {
        if (!(value >= 0.05f && value <= 100f)) throw new IllegalArgumentException("stiffness must be 0.05..100");
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("value", value);
        return SoftBodies.call("stiffness", a).get("stiffness").getAsDouble();
    }

    /** Damping in 1/s, 0 to 50. */
    public void setDamping(int id, float value) {
        if (!(value >= 0f && value <= 50f)) throw new IllegalArgumentException("damping must be 0..50");
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("value", value);
        SoftBodies.call("damping", a);
    }

    /** Particle positions as x, y, z triples in world coordinates, copied. Empty if the id is unknown. */
    public float[] particlePositions(int id) {
        if (!ids().contains(id)) return new float[0];
        JsonArray pos = SoftBodies.withId("positions", id).getAsJsonArray("pos");
        float[] out = new float[pos.size()];
        for (int i = 0; i < out.length; i++) out[i] = pos.get(i).getAsFloat();
        return out;
    }

    /** True when the soft-body system stopped after an error and no longer steps. */
    public boolean isBroken() {
        return SoftBodies.isBroken();
    }

    /** One line for diagnostics. */
    static String describeState() {
        try {
            JsonObject listed = SoftBodies.call("list", new JsonObject());
            return listed.getAsJsonArray("bodies").size() + " of " + MAX_BODIES + (SoftBodies.isBroken() ? " (stopped)" : "");
        } catch (RuntimeException e) {
            return "unavailable";
        }
    }
}
