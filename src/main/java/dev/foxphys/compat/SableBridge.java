package dev.foxphys.compat;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Optional Sable Companion integration. Sable is an optional dependency: without the mod
 * (or without its Companion API) every call falls back to plain vanilla geometry.
 *
 * The Companion jar is bundled as a nested Fabric jar (META-INF/jars) and used through
 * reflection. Its build targets a newer Loom and Sable's classes are not on our compile
 * classpath, so no compile-time or remap-time link exists. Nothing here loads Sable classes
 * unless {@link #available()} has confirmed that Sable is present.
 */
public final class SableBridge {
    private SableBridge() {}

    private static final String API_CLASS = "dev.ryanhcode.sable.companion.SableCompanion";
    private static final String DISTANCE = "distanceSquaredWithSubLevels";
    private static final Object API;
    private static final Method DISTANCE_METHOD;

    static {
        Object api = null;
        Method distance = null;
        if (FabricLoader.getInstance().isModLoaded("sable")) {
            try {
                Class<?> type = Class.forName(API_CLASS, true, SableBridge.class.getClassLoader());
                api = type.getField("INSTANCE").get(null);
                // (Level, double x6) -> double. The first parameter is the world type; match by name and arity.
                for (Method m : type.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals(DISTANCE) && p.length == 7 && m.getReturnType() == double.class
                            && p[1] == double.class && p[6] == double.class) {
                        distance = m;
                        break;
                    }
                }
                if (distance == null) api = null;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                api = null;
                distance = null;
            }
        }
        API = api;
        DISTANCE_METHOD = distance;
    }

    /** True only when Sable and its Companion API are both loaded. */
    public static boolean available() { return API != null; }

    /**
     * Squared distance between two world points, measured through Sable sub-levels when
     * Sable is present (a player standing on a moving structure is then measured in the
     * structure's frame). Otherwise the plain vanilla distance.
     */
    public static double distanceSquared(World world, Vec3d a, Vec3d b) {
        if (API != null) {
            try {
                Method m = DISTANCE_METHOD;
                if (m.getParameterTypes()[0].isInstance(world)) {
                    return (double) m.invoke(API, world, a.x, a.y, a.z, b.x, b.y, b.z);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // An optional integration must never break a physics tick: fall back to vanilla.
            }
        }
        return a.squaredDistanceTo(b);
    }
}
