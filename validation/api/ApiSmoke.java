import dev.foxphys.DensityTable;
import dev.foxphys.api.FoxPhysApi;
import dev.foxphys.api.SoftBodyApi;
import dev.foxphys.api.SoftBodyInfo;
import dev.foxphys.api.event.AssembleRequestedEvent;
import dev.foxphys.api.event.SoftBodyRemovedEvent;
import dev.foxphys.api.event.SoftBodySpawnedEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs the NeoForge API without a world: native kernel, densities, soft bodies, events.
 * Rigid-body methods need a ServerLevel and are not covered here.
 * Run with validation/api/run-neoforge-smoke.sh.
 */
public class ApiSmoke {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fails++;
    }

    static void expectIae(String name, Runnable r) {
        try {
            r.run();
            check(name + " (no exception)", false);
        } catch (IllegalArgumentException e) {
            check(name, true);
        } catch (RuntimeException e) {
            System.out.println("   got " + e);
            check(name, false);
        }
    }

    public static void main(String[] args) {
        check("API_VERSION == 1", FoxPhysApi.API_VERSION == 1);
        check("isCompatible(1)", FoxPhysApi.isCompatible(1));
        check("isCompatible(2) is false", !FoxPhysApi.isCompatible(2));
        check("native loads", FoxPhysApi.isNativeLoaded());
        System.out.println("describe: " + FoxPhysApi.describe());

        // densities
        int before = DensityTable.size();
        FoxPhysApi.registerDensity("testmod:rock", 3100);
        check("full-id override stored", DensityTable.get("testmod:rock") == 3100.0);
        FoxPhysApi.registerDensity("stone", 1234);
        check("path override replaces table value", DensityTable.get("stone") == 1234.0);
        check("table size unchanged by overrides", DensityTable.size() == before);
        check("override count", FoxPhysApi.registeredDensityCount() == 2);
        check("registeredDensities has full id", FoxPhysApi.registeredDensities().get("testmod:rock") == 3100.0);
        expectIae("density 0 refused", () -> FoxPhysApi.registerDensity("x", 0));
        expectIae("NaN density refused", () -> FoxPhysApi.registerDensity("x", Double.NaN));
        expectIae("too big density refused", () -> FoxPhysApi.registerDensity("x", 1e9));
        expectIae("empty key refused", () -> FoxPhysApi.registerDensity(" ", 100));
        check("unregister known key", FoxPhysApi.unregisterDensity("stone"));
        check("unregister unknown key is false", !FoxPhysApi.unregisterDensity("nothing_here"));
        check("path falls back to table after unregister", DensityTable.get("stone") == 2650.0);
        try {
            FoxPhysApi.registeredDensities().put("x", 1.0);
            check("registered copy is read-only", false);
        } catch (UnsupportedOperationException e) {
            check("registered copy is read-only", true);
        }

        // soft bodies, with events
        NeoForge.EVENT_BUS.start(); // the loader starts the bus in the game; a bare JVM must do it itself
        List<Object> spawned = new ArrayList<>();
        NeoForge.EVENT_BUS.addListener(SoftBodySpawnedEvent.class, e -> spawned.add(e.preset()));
        List<Object> removed = new ArrayList<>();
        NeoForge.EVENT_BUS.addListener(SoftBodyRemovedEvent.class, e -> removed.add(e.bodyId()));

        SoftBodyApi soft = FoxPhysApi.soft();
        int id = soft.spawn("rope", new Vec3(0, 5, 0), 0);
        check("spawn returns id", id > 0);
        check("spawn event fired", spawned.equals(List.of("rope")));
        check("ids contains id", soft.ids().contains(id));
        check("count == 1", soft.count() == 1);
        Optional<SoftBodyInfo> info = soft.info(id);
        check("info present", info.isPresent());
        SoftBodyInfo si = info.orElseThrow();
        System.out.println("info: " + si);
        check("preset rope", si.preset().equals("rope"));
        check("particles > 0", si.particles() > 0);
        check("finite", si.finite());
        check("pin start > 0", soft.pin(id, "start") > 0);
        check("pinParticle", soft.pinParticle(id, si.particles() - 1) == 1);
        check("stiffness set", Math.abs(soft.setStiffness(id, 2.0f) - 2.0) < 1e-6);
        soft.setDamping(id, 1.0f);
        float[] pos = soft.particlePositions(id);
        check("positions length 3*particles", pos.length == 3 * si.particles());
        boolean allFinite = true;
        for (float f : pos) allFinite &= Float.isFinite(f);
        check("positions finite", allFinite);
        expectIae("unknown preset refused", () -> soft.spawn("gel", new Vec3(0, 0, 0), 0));
        expectIae("NaN origin refused", () -> soft.spawn("rubber", new Vec3(Double.NaN, 0, 0), 0));
        expectIae("bad pin side refused", () -> soft.pin(id, "middle"));
        expectIae("stiffness out of range refused", () -> soft.setStiffness(id, 500f));
        check("unknown info empty", soft.info(999999).isEmpty());
        check("unknown positions empty", soft.particlePositions(999999).length == 0);
        soft.remove(id);
        check("remove event fired", removed.equals(List.of(id)));
        check("gone after remove", soft.info(id).isEmpty());
        check("not broken", !soft.isBroken());

        // cancellable event semantics on the bus (no world needed)
        NeoForge.EVENT_BUS.addListener(AssembleRequestedEvent.class, e -> e.setCanceled(true));
        AssembleRequestedEvent ev = new AssembleRequestedEvent(null, List.of());
        NeoForge.EVENT_BUS.post(ev);
        check("cancellable event reports canceled", ev.isCanceled());

        System.out.println(fails == 0 ? "API_SMOKE_OK" : "API_SMOKE_FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
