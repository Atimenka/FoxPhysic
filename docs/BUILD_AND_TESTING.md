# Build and testing

This page covers reproducible builds, native-library packaging, headless checks, and the boundary between those checks and a real game test.

## Requirements and workspace layout

- **JDK 21** for Minecraft 1.21.1 and Gradle.
- Internet on first build for Gradle/Minecraft/loader dependencies and Rust crates.
- To rebuild native code, use the pinned toolchain in `native/rust-toolchain.toml` (Rust 1.89.0, `rustfmt`, `clippy`). Players do not need Rust.
- The source modules are `common/`, `fabric/` and `neoforge/`. Loader-specific Java sources are mapped separately; common protocol/native-loader classes live in `common/`.
- Root Gradle uses one worker, disables parallel execution and has a 1.5 GiB heap. The standalone NeoForge build uses a 3 GiB heap. Both remain within the user's 10 GB RAM limit; avoid running independent Gradle daemons concurrently.

## Runtime configuration

On first launch FoxPhys writes `config/foxphys.json` using the same snake-case keys on Fabric and NeoForge. Values are read at startup; restart the game/server after editing. Defaults:

```json
{
  "gravity": 9.81,
  "air_density": 1.225,
  "water_density": 998.2,
  "step_hz": 120,
  "max_bodies": 64,
  "environment_scan_interval": 1,
  "visual_update_interval": 1,
  "water_display_stride": 2
}
```

| Setting | Default | Accepted range | Units and effect |
|---|---:|---:|---|
| `gravity` | 9.81 | 0.1–30 | m/s²; magnitude of downward gravity. |
| `air_density` | 1.225 | 0–20 | kg/m³; rigid-body air-force model. |
| `water_density` | 998.2 | 100–2000 | kg/m³; rigid-body buoyancy. |
| `step_hz` | 120 | 30–240 | Fixed physics steps per simulated second. Higher rates cost more CPU. |
| `max_bodies` | 64 | 0–10,000 | Native rigid-body cap; `0` disables this cap, not assembly/collider limits. |
| `environment_scan_interval` | 1 | 1–200 | Server ticks between terrain scans while a player is nearby. Larger values lower scan frequency but can delay world edits reaching physics. |
| `visual_update_interval` | 1 | 1–10 | Server ticks between rigid-body visual updates while a player is nearby. Larger values reduce work but can make motion less smooth. |
| `water_display_stride` | 2 | 1–4 | Groups reservoir cells for display entities; changes display resolution only. |

If the file is unreadable or a value is outside its range, the loader logs an error and uses in-memory defaults for that run; it does not silently clamp the invalid value. Configuration does not override terrain-scan, assembly, or native world-handle limits.

## Build and test the Rust native library

```bash
cd native
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
cargo build --locked --release
```

Linux x64 output: `native/target/release/libfoxphys_core.so`. The Windows x64 DLL is cross-built by the agent with the MinGW-w64 GNU target and is staged in `neoforge/prebuilt/natives/windows-x86_64/`; there is no Visual Studio requirement for users. The Windows DLL depends only on Windows system DLLs (not separate MinGW runtime DLLs).

Release performance checks are ignored tests, so run them explicitly:

```bash
cargo test --locked --release --lib bench_tick -- --ignored --nocapture
cargo test --locked --release --lib soft_budget_1000_particles -- --ignored --nocapture
cargo test --locked --release --lib soft_active_budget_1000_particles -- --ignored --nocapture
```

The SPH benchmark measures 320/600-particle still pools without external block/rigid colliders. The XPBD benchmark uses a 1,000-particle rest-shape workload, 4 XPBD iterations and self-collision enabled. These are native CPU-harness measurements, not Minecraft server TPS guarantees.

Optional Windows GNU cross-build from Linux (no Visual Studio): install MinGW-w64 GCC, then run:

```bash
./scripts/package-cross-windows.sh
```

The script builds the Windows DLL, updates the staged fallback native, and runs the root Gradle build. Keep the generated target under `native/target/`; it is disposable and can be recreated.

## Build both loader jars

From the repository root on Linux:

```bash
bash ./gradlew --no-daemon --console=plain --max-workers=1 build
```

On Windows PowerShell:

```powershell
./gradlew.bat --no-daemon --max-workers=1 build
```

The root build includes `:common`, `:fabric` and `:neoforge`. By default it packages the agent-built native libraries staged in `neoforge/prebuilt/natives/`; it does **not** require a Rust compiler or Visual Studio on the player's laptop. Use `-PcompileNative` only when you want Gradle to rebuild the host native library before packaging. A normal Linux build packages the Linux `.so` and Windows `.dll` in both jars.

Distributables:

- `fabric/build/libs/foxphys-fabric-0.1.1-beta-release.jar`
- `neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release.jar`

Source jars are beside them. The current `mod_version` is `0.1.1-beta-release`. `-PskipNative` is a Java/package-only mode that skips native staging and JNI/protocol smoke tasks; jars built that way do not contain the required native resources.

NeoForge embeds the NeoForge-compatible Sable Companion artifact with Jar-in-Jar. Verify the produced archive rather than only its Gradle task graph:

```bash
jar tf neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release.jar | grep 'META-INF/jarjar'
unzip -p neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release.jar META-INF/jarjar/metadata.json
```

Expected: JarJar metadata and the nested Sable Companion common jar are present. Fabric continues to bundle its Fabric-compatible Companion jar in `META-INF/jars/` for optional compatibility.

## Headless checks

A full root build runs Fabric's JUnit, JNI and protocol checks as part of `check`. The standalone scripts compile against the Minecraft/loader dependencies and use the Linux native library. After `compileJava`/`build`, run from the root:

```bash
sh validation/api/run-fabric-smoke.sh
sh validation/api/run-neoforge-smoke.sh
sh validation/fluid/run-fluid-smoke.sh
sh validation/attach/run-attach-smoke.sh
```

Expected markers: `API_SMOKE_OK` from both API scripts, `FLUID_SMOKE_OK` and `ATTACH_SMOKE_OK`. The API smoke surface checks force/torque, angular velocity, block-state lookup, density/provider registration and client snapshots on both loader mappings. The JNI/protocol task checks native loading and JSON operations. Set `FOXPHYS_NATIVE_PATH=/path/to/libfoxphys_core.so` to test a freshly built library without changing the fallback first.

These checks exercise selected API invariants, JNI, protocol, water and soft-body attachment/coupling operations without starting Minecraft. They do not confirm rendering, mixin behaviour, controls, player contact, bucket interactions or end-to-end server TPS. The terrain-scanning microbenchmark is in `validation/scan-optimization/`; it isolates Java-side collection/key bookkeeping only.

## In-game verification

Use the current [in-game checklist](IN_GAME_TEST_CHECKLIST.md) on a separate disposable world, with the exact jar and SHA256 recorded. Test client and server together on each loader; capture both `latest.log` files and any crash report. **Do not report any live criterion as passed unless a Minecraft client was actually launched and the behaviour was observed.**

## Release artifacts and cleanup

The ready-to-install alpha.7 jars, source jars, icon and checksum list remain in `release/alpha.7/`. Verify those historical artifacts from that directory with:

```bash
cd release/alpha.7 && sha256sum -c SHA256SUMS.txt
```

The 0.1.1-beta-release source bundle is not a compiled release. Build it using the commands above, run the headless checks, and complete `docs/IN_GAME_TEST_CHECKLIST.md` before publishing installable beta jars.

`fabric/build/`, `neoforge/build/`, Gradle caches and `native/target/` are generated. Do not delete source files, validation harnesses, prebuilt runtime natives, or the `neoforge/run/` game directory/world while cleaning. Only clear generated caches/artifacts after confirming they are not needed for an in-progress build or smoke test.
