package dev.foxphys.fluid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;

/**
 * One pool of SPH water (3D weakly compressible SPH, native side in fluid_world.rs).
 *
 * The pool is an open-top box anchored at a world block corner. Its water is self-contained: it does not
 * push or float rigid bodies yet. It advances only while a player is near, and it does not catch up after
 * being unobserved, so the water never jumps.
 */
public final class FluidPool {
    /** Server ticks are 50 ms of simulated time per call. */
    public static final double TICK_SECONDS = 0.05;
    /** Substep cap per tick. When the cap binds, the pool runs slower than real time; {@link #lag()} reports it. */
    public static final int MAX_STEPS = 60;

    public static final double SIZE = 0.4;
    public static final double DX = 0.05;
    public static final double FILL_HEIGHT = 0.2;

    private final int id;
    private final ServerWorld level;
    private final BlockPos origin;
    private final String shape;
    private final int particles;
    private float[] local = new float[0];
    private double lag = 1.0;
    private double simTime;
    private boolean closed;

    private FluidPool(int id, ServerWorld level, BlockPos origin, String shape, int particles) {
        this.id = id;
        this.level = level;
        this.origin = origin.toImmutable();
        this.shape = shape;
        this.particles = particles;
    }

    /** Creates a pool. {@code dam}: a column in the left 40 % with twice the fill height. */
    public static FluidPool create(ServerWorld level, BlockPos origin, boolean dam) {
        NativeLoader.ensureLoaded();
        String shape = dam ? "dam" : "still";
        JsonObject req = new JsonObject();
        req.addProperty("op", "create");
        JsonArray size = new JsonArray();
        size.add(SIZE); size.add(SIZE); size.add(SIZE);
        req.add("size", size);
        req.addProperty("fill_height", FILL_HEIGHT);
        req.addProperty("dx", DX);
        req.addProperty("shape", shape);
        JsonObject r = call(req);
        FluidPool pool = new FluidPool(r.get("id").getAsInt(), level, origin, shape, r.get("particles").getAsInt());
        pool.advance(false); // read initial positions without stepping
        return pool;
    }

    private static JsonObject call(JsonObject request) {
        return JsonParser.parseString(NativeBridge.fluidExchange(request.toString())).getAsJsonObject();
    }

    /** Advances one tick when {@code near}; always refreshes the particle positions. */
    public void advance(boolean near) {
        if (closed) return;
        if (near) {
            JsonObject step = new JsonObject();
            step.addProperty("op", "step");
            step.addProperty("id", id);
            step.addProperty("seconds", TICK_SECONDS);
            step.addProperty("max_steps", MAX_STEPS);
            JsonObject r = call(step);
            double advanced = r.get("advanced").getAsDouble();
            lag = advanced / TICK_SECONDS;
            simTime = r.get("sim_time").getAsDouble();
        }
        JsonObject pos = new JsonObject();
        pos.addProperty("op", "positions");
        pos.addProperty("id", id);
        JsonArray xyz = call(pos).getAsJsonArray("xyz");
        float[] out = new float[xyz.size()];
        for (int i = 0; i < out.length; i++) out[i] = xyz.get(i).getAsFloat();
        local = out;
    }

    public void close() {
        if (closed) return;
        closed = true;
        JsonObject req = new JsonObject();
        req.addProperty("op", "remove");
        req.addProperty("id", id);
        try {
            call(req);
        } catch (RuntimeException e) {
            // The native registry may already be gone (shutdown); nothing else to release.
        }
    }

    public int id() { return id; }
    public ServerWorld level() { return level; }
    public BlockPos origin() { return origin; }
    public String shape() { return shape; }
    public int particles() { return particles; }
    /** Particle positions relative to the origin, x y z per particle. */
    public float[] local() { return local; }
    /** Fraction of the requested simulated time that the last tick managed (1 = real time). */
    public double lag() { return lag; }
    public double simTime() { return simTime; }
    public boolean closed() { return closed; }
}
