package dev.foxphys;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxphys.api.event.SoftBodyRemovedEvent;
import dev.foxphys.api.event.SoftBodySpawnedEvent;
import dev.foxphys.net.FoxPhysNetwork;
import net.neoforged.neoforge.common.NeoForge;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Java side of the soft bodies (XPBD in Rust). Rust owns all state; Java sends JSON requests,
 * steps the registry once per server tick (1/20 s, 8 substeps) while bodies exist, and reports results.
 * Soft bodies do not collide with blocks; the only contact is a flat floor plane given at spawn.
 */
public final class SoftBodies {
    private SoftBodies() {}

    static final double TICK_DT = 0.05;
    static final int SUBSTEPS = 8;
    /** Bodies known to Rust (updated from responses). */
    private static volatile int count = 0;
    private static volatile boolean broken = false;
    /** Consecutive ticks in which the view (sending shapes to clients) failed. Reset on success; never disables the view. */
    private static int viewFailures = 0;
    /** True after at least one non-empty shape update, so an empty update can clear the clients. */
    private static boolean published = false;
    /** Ticks since the surface triangles were last sent in full (see publish). */
    private static int ticksSinceFullTris = 0;
    /** Bodies whose surface reached the clients in the last full send. */
    private static Set<Integer> trisSent = Set.of();
    static final int TRIS_EVERY_TICKS = 20;

    /** One JSON round trip. Throws IllegalStateException with the Rust message on error. */
    public static JsonObject call(String op, JsonObject args) {
        NativeLoader.ensureLoaded();
        args.addProperty("op", op);
        String text = NativeBridge.softExchange(args.toString());
        JsonObject out = JsonParser.parseString(text).getAsJsonObject();
        if (out.has("bodies") && out.get("bodies").isJsonPrimitive()) {
            count = out.get("bodies").getAsInt();
        }
        return out;
    }

    public static JsonObject spawn(String preset, double x, double y, double z, double floorY) {
        JsonObject a = new JsonObject();
        a.addProperty("preset", preset);
        var origin = new com.google.gson.JsonArray();
        origin.add(x); origin.add(y); origin.add(z);
        a.add("origin", origin);
        a.addProperty("floor", floorY);
        JsonObject out = call("spawn", a);
        count++;
        NeoForge.EVENT_BUS.post(new SoftBodySpawnedEvent(out.get("id").getAsInt(), preset));
        return out;
    }

    /** Removes one soft body and fires {@link SoftBodyRemovedEvent}. Throws IllegalStateException if Rust refuses. */
    public static void remove(int id) {
        SoftAttachments.forget(id);
        withId("remove", id);
        NeoForge.EVENT_BUS.post(new SoftBodyRemovedEvent(id));
    }

    public static JsonObject withId(String op, int id) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        return call(op, a);
    }

    /** Sets the air wind (m/s, world axes) for all soft bodies. Ignored while no body exists. */
    public static void setWind(net.minecraft.world.phys.Vec3 velocity) {
        if (count <= 0 || broken || windUnsupported) return;
        try {
            JsonObject a = new JsonObject();
            a.add("velocity", Protocol.vector(velocity.x, velocity.y, velocity.z));
            call("wind", a);
        } catch (RuntimeException e) {
            // Usually an old native library without the op: say so once instead of every tick.
            windUnsupported = true;
            FoxPhysMod.LOGGER.warn("FoxPhys: wind for soft bodies is off ({}). Update the native library.", e.getMessage());
        }
    }

    /** True after the native library refused the wind op (no wind for soft bodies). */
    private static volatile boolean windUnsupported = false;

    /** Advances all bodies by one server tick. Errors stop stepping and are logged once. */
    public static void tick() {
        if (count <= 0 || broken) return;
        try {
            JsonObject a = new JsonObject();
            a.addProperty("dt", TICK_DT);
            a.addProperty("substeps", SUBSTEPS);
            call("step", a);
        } catch (RuntimeException e) {
            broken = true;
            FoxPhysMod.LOGGER.error("FoxPhys soft bodies stopped after an error: {}", e.getMessage());
        }
    }

    /**
     * Sends the current shape of every soft body to all players (once per server tick, after tick()).
     * The surface triangles are sent in full when a body appears and then every {@link #TRIS_EVERY_TICKS}
     * ticks, so a player who joins later gets them within a second. In between, a mesh carries an empty
     * triangle list and the client keeps the copy it has. An empty list is sent once when the last body
     * goes, so the clients drop their copies.
     */
    public static void publish() {
        if (broken) return;
        if (count <= 0) {
            if (published) {
                FoxPhysNetwork.sendSoftMeshes(List.of());
                published = false;
            }
            trisSent = Set.of();
            return;
        }
        try {
            JsonObject listed = call("list", new JsonObject());
            JsonArray bodies = listed.getAsJsonArray("bodies");
            Set<Integer> ids = new HashSet<>();
            for (JsonElement element : bodies) ids.add(element.getAsInt());
            boolean full = ticksSinceFullTris >= TRIS_EVERY_TICKS || !trisSent.containsAll(ids);
            List<FoxPhysNetwork.SoftMesh> meshes = new ArrayList<>();
            for (JsonElement element : bodies) {
                JsonObject request = new JsonObject();
                request.addProperty("id", element.getAsInt());
                if (!full) request.addProperty("tris", false);
                JsonObject shape = call("positions", request);
                int[] tris = full ? ints(shape.getAsJsonArray("tris")) : new int[0];
                meshes.add(new FoxPhysNetwork.SoftMesh(
                        shape.get("id").getAsInt(),
                        shape.get("preset").getAsString(),
                        floats(shape.getAsJsonArray("pos")),
                        tris));
            }
            FoxPhysNetwork.sendSoftMeshes(meshes);
            published = !meshes.isEmpty();
            if (full) {
                trisSent = ids;
                ticksSinceFullTris = 0;
            } else {
                ticksSinceFullTris++;
            }
            if (viewFailures > 0) {
                FoxPhysMod.LOGGER.info("FoxPhys soft body view recovered after {} failed ticks", viewFailures);
                viewFailures = 0;
            }
        } catch (RuntimeException e) {
            // A failed tick is retried on the next one. The first failure of a streak is logged with its stack.
            trisSent = Set.of();
            if (viewFailures++ == 0) FoxPhysMod.LOGGER.error("FoxPhys soft body view failed, retrying each tick: {}", e.getMessage(), e);
        }
    }

    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsFloat();
        return out;
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }

    public static boolean isBroken() {
        return broken;
    }

    public static void clearAll() {
        SoftAttachments.clear();
        count = 0;
        trisSent = Set.of();
    }
}
