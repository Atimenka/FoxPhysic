package dev.foxphys;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxphys.nativecore.NativeBridge;
import dev.foxphys.nativecore.NativeLoader;

/**
 * Smoke test for the SPH water pools through the JNI bridge, without a server.
 * Creates a still pool and a dam-break pool, steps them, checks mass, finiteness, positions and
 * the error path, then removes them.
 */
public final class FluidSmoke {
    private static JsonObject call(String json) {
        return JsonParser.parseString(NativeBridge.fluidExchange(json)).getAsJsonObject();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FLUID_SMOKE_FAIL: " + what);
        System.out.println("ok: " + what);
    }

    public static void main(String[] args) {
        NativeLoader.ensureLoaded();
        JsonObject still = call("{\"op\":\"create\",\"size\":[0.4,0.4,0.4],\"fill_height\":0.2,\"dx\":0.05,\"shape\":\"still\"}");
        int a = still.get("id").getAsInt();
        JsonObject dam = call("{\"op\":\"create\",\"size\":[0.4,0.4,0.4],\"fill_height\":0.2,\"dx\":0.05,\"shape\":\"dam\"}");
        int b = dam.get("id").getAsInt();
        check(still.get("particles").getAsInt() > dam.get("particles").getAsInt(), "dam pool has fewer particles than still pool");

        JsonObject before = call("{\"op\":\"status\",\"id\":" + a + "}");
        double mass0 = before.get("mass").getAsDouble();
        JsonObject step = call("{\"op\":\"step\",\"id\":" + a + ",\"seconds\":0.05,\"max_steps\":60}");
        check(Math.abs(step.get("advanced").getAsDouble() - 0.05) < 1e-9, "one tick advances 0.05 s of simulated time");
        for (int i = 0; i < 19; i++) call("{\"op\":\"step\",\"id\":" + b + ",\"seconds\":0.05,\"max_steps\":60}");
        JsonObject after = call("{\"op\":\"status\",\"id\":" + a + "}");
        check(after.get("mass").getAsDouble() == mass0, "water mass is conserved");
        double ke = after.get("kinetic_energy").getAsDouble();
        check(Double.isFinite(ke) && ke < 50.0, "still pool stays finite and bounded after one tick, ke " + ke);

        JsonObject pos = call("{\"op\":\"positions\",\"id\":" + b + "}");
        check(pos.getAsJsonArray("xyz").size() == 3 * pos.get("n").getAsInt(), "positions are flat x y z");
        boolean finite = true;
        for (var v : pos.getAsJsonArray("xyz")) finite &= Double.isFinite(v.getAsDouble());
        check(finite, "positions are finite");

        boolean rejected;
        try {
            NativeBridge.fluidExchange("{\"op\":\"step\",\"id\":999,\"seconds\":0.05}");
            rejected = false;
        } catch (IllegalStateException e) {
            rejected = true;
        }
        check(rejected, "unknown pool id is rejected with an error");

        call("{\"op\":\"remove\",\"id\":" + a + "}");
        call("{\"op\":\"remove\",\"id\":" + b + "}");
        check(call("{\"op\":\"list\"}").getAsJsonArray("pools").size() == 0, "pools removed");
        System.out.println("FLUID_SMOKE_OK");
    }
}
