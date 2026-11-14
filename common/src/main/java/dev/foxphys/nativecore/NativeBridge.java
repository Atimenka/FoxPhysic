package dev.foxphys.nativecore;

/** JNI ABI v1. These names must match Rust's exported symbols exactly. */
public final class NativeBridge {
    public static final int ABI = 1;
    private NativeBridge() {}
    public static native int abiVersion();
    /** Synchronous world (used by tests and tools). */
    public static native long create(String configurationJson);
    public static native String exchange(long handle, String requestJson);
    public static native void destroy(long handle);

    /** Physics worker: one thread per lab, stepping in wall-clock time. */
    public static native long workerCreate(String configurationJson);
    /** Fire-and-forget input (terrain, water cells). Never waits for the physics step. */
    public static native void workerPush(long handle, String requestJson);
    /** Synchronous input for commands, export and import. Waits at most two seconds. */
    public static native String workerCall(long handle, String requestJson);
    /** Latest published snapshot JSON, or null before the first step. */
    public static native String workerLatest(long handle);
    /** "" when nothing is pending, otherwise "F:..." (fatal) or "R:..." (rejected input). */
    public static native String workerTakeError(long handle);
    public static native void workerSetIdle(long handle, boolean idle);
    public static native void workerDestroy(long handle);

    /** Soft bodies (XPBD): one JSON request, one JSON response. Throws IllegalStateException on error. */
    public static native String softExchange(String requestJson);

    /** SPH water pools: one JSON request, one JSON response (see native fluid_world.rs). Throws IllegalStateException on error. */
    public static native String fluidExchange(String requestJson);
}
