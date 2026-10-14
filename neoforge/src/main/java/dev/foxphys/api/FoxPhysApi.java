package dev.foxphys.api;

import dev.foxphys.DensityTable;
import dev.foxphys.FoxPhysMod;
import dev.foxphys.Lab;
import dev.foxphys.nativecore.NativeLoader;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Public entry point of FoxPhys for other mods. Everything here is stable within one {@link #API_VERSION}.
 *
 * <p>Threading: call every method on the server thread. Calls from another thread throw
 * {@link IllegalStateException} before anything changes. Results are views of the last completed
 * physics step; a change made in one call is visible in the snapshot after the next tick.
 *
 * <p>Errors: invalid arguments throw {@link IllegalArgumentException}; a physics world that does not
 * exist or failed throws {@link IllegalStateException}. Nothing in this API throws for a body that
 * is missing: lookups return {@link Optional#empty()}.
 *
 * <p>Versions: check {@link #isCompatible(int)} at start-up if your mod needs a newer method.
 *
 * <p>Compatibility: classes under {@code dev.foxphys} other than this package are internal and may
 * change in any release. Use only {@code dev.foxphys.api} and {@code dev.foxphys.api.event}.
 */
public final class FoxPhysApi {
    /**
     * Version of this API. Additions keep the number; removing or changing the meaning of a method
     * raises it. Compare with {@code >=} before you rely on a newer method.
     */
    public static final int API_VERSION = 1;

    private static final SoftBodyApi SOFT = new SoftBodyApi();

    private FoxPhysApi() {}

    /** True when this FoxPhys provides API version {@code minimumVersion} or newer. */
    public static boolean isCompatible(int minimumVersion) {
        return API_VERSION >= minimumVersion;
    }

    /** True when the native physics library loads on this machine. False means no physics anywhere. */
    public static boolean isNativeLoaded() {
        try {
            NativeLoader.ensureLoaded();
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The physics world of a dimension, if it exists and has not failed. */
    public static Optional<PhysicsWorld> physics(ServerLevel level) {
        requireServerThread(level);
        Lab lab = FoxPhysMod.manager().labFor(level);
        if (lab == null || lab.failed()) return Optional.empty();
        return Optional.of(new PhysicsWorld(level));
    }

    /**
     * The physics world of a dimension, created on first use with its origin at {@code anchor}.
     * The origin is the corner of the lab, in world coordinates; bodies live in a box around it.
     *
     * @throws IllegalStateException if the native library is not loaded or the world cannot start
     */
    public static PhysicsWorld ensurePhysics(ServerLevel level, BlockPos anchor) {
        if (anchor == null) throw new IllegalArgumentException("anchor is null");
        requireServerThread(level);
        Lab lab = FoxPhysMod.manager().ensure(level, anchor);
        if (lab.failed()) throw new IllegalStateException("Physics world failed to start");
        return new PhysicsWorld(level);
    }

    /** Soft bodies (XPBD cloth, rubber, jelly, rope). They are independent of the rigid-body worlds. */
    public static SoftBodyApi soft() {
        return SOFT;
    }

    /**
     * Density (kg/m^3) for a block, as physics uses it when the block is assembled. Keys are block
     * paths ({@code "oak_planks"}, applies to every namespace) or full ids ({@code "mymod:rock"},
     * applies to that block only); a full id wins over a path.
     *
     * <p>Only bodies assembled after the call use the new value. Bodies that already exist keep their mass.
     *
     * @throws IllegalArgumentException if the key is empty or the density is outside (0, 100000]
     */
    public static void registerDensity(String key, double kgPerM3) {
        DensityTable.register(key, kgPerM3);
    }

    /** The density physics uses for a block right now (table, registered value or name heuristic). */
    public static double densityOf(BlockState state) {
        if (state == null) throw new IllegalArgumentException("state is null");
        return Lab.density(state);
    }

    /** Removes a density registered through {@link #registerDensity}. Returns false if the key was not registered. */
    public static boolean unregisterDensity(String key) {
        return DensityTable.unregister(key);
    }

    /** The densities registered through the API, as a copy: key (path or full id) to kg/m^3. */
    public static Map<String, Double> registeredDensities() {
        return DensityTable.registered();
    }

    /** Number of densities registered through {@link #registerDensity}. */
    public static int registeredDensityCount() {
        return DensityTable.overrideCount();
    }

    /**
     * Throws IllegalStateException when the caller is not on the server thread of the level's server.
     * Package-private: the other API classes in this package share this one check.
     */
    static void requireServerThread(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("level is null");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("FoxPhys API must be called on the server thread");
        }
    }

    /** One line of text for logs and diagnostics. */
    public static String describe() {
        return String.format(Locale.ROOT, "FoxPhys API v%d, native %s, density table %d, registered densities %d, soft bodies %s",
                API_VERSION, isNativeLoaded() ? "loaded" : "NOT loaded", DensityTable.size(),
                DensityTable.overrideCount(), SoftBodyApi.describeState());
    }
}
