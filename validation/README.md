# Headless validation tools

This folder contains executable checks, benchmark source code, and instructions for reproducing them. Store generated logs and completed test reports outside the source tree unless they are needed to diagnose a current defect.

## Requirements

- JDK 21.
- A completed Gradle dependency resolution for the loader being tested.
- The Linux x64 native library `neoforge/prebuilt/natives/linux-x86_64/libfoxphys_core.so` for JNI smoke scripts.

Set the same `JAVA_HOME` and `GRADLE_USER_HOME` used for the build. From the repository root:

```bash
export JAVA_HOME=/path/to/jdk-21
export GRADLE_USER_HOME=/path/to/gradle-cache
```

## Smoke scripts

```bash
sh validation/api/run-fabric-smoke.sh
sh validation/api/run-neoforge-smoke.sh
sh validation/fluid/run-fluid-smoke.sh
sh validation/attach/run-attach-smoke.sh
```

Expected markers: `API_SMOKE_OK` from each API script, `FLUID_SMOKE_OK`, and `ATTACH_SMOKE_OK`. The source harnesses they compile are kept next to their scripts. By default they load the checked-in Linux library; set `FOXPHYS_NATIVE_PATH=/path/to/libfoxphys_core.so` to test a freshly built native library without replacing the packaged fallback.

The regular Fabric Gradle `build` runs `jniSmoke` and `protocolSmoke` against the staged Linux library. Both Fabric and NeoForge `check` tasks also run `verifyBundledCommonClasses`: they inspect the final mod archive and load `NativeLoader`/`NativeBridge` through an isolated classloader, so a `common` compile dependency cannot silently be omitted from a release jar. Native compilation is opt-in (`-PcompileNative`); by default Gradle uses the checked-in agent-built native fallback. With `-PskipNative`, native staging and the Gradle JNI/protocol smoke tasks are skipped; use the standalone scripts if the prebuilt Linux library is available.

For a manual release-archive class-loading smoke after both builds:

```bash
mkdir -p /tmp/foxphys-packaging-smoke
javac --release 21 -d /tmp/foxphys-packaging-smoke validation/packaging/PackagedLoaderSmoke.java
java -cp /tmp/foxphys-packaging-smoke PackagedLoaderSmoke release/alpha.7/foxphys-fabric-0.1.0-alpha.7.jar
java -cp /tmp/foxphys-packaging-smoke PackagedLoaderSmoke release/alpha.7/foxphys-neoforge-0.1.0-alpha.7.jar
```

## Coverage and limits

- **API:** version/compatibility checks, density registration and lookup, soft-body operations, and event cancellation, on both loader mappings.
- **Fluid:** create/step/status/remove, finite positions, and volume/mass checks for SPH pools.
- **Attachment:** vector rotation, anchored particle targets, release, and wind input.
- **Terrain bookkeeping:** `scan-optimization/TerrainScanMicrobench.java` compares boxed/primitive cell deduplication and JSON-string/typed terrain keys.

These are headless harnesses, not a Minecraft client or a real multi-player server. They cannot confirm rendering, mixin behaviour, controls, player contact, or end-to-end server TPS. Use [`docs/IN_GAME_TEST_CHECKLIST.md`](../docs/IN_GAME_TEST_CHECKLIST.md) for game checks. Build commands and the full scope are in [`docs/BUILD_AND_TESTING.md`](../docs/BUILD_AND_TESTING.md).
