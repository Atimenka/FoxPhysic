# FoxPhys 0.1.0-alpha.7 closure status

Updated: 2026-10-28. This tracker records implementation and verification evidence for the alpha.7 acceptance criteria. A compile, native unit test or headless smoke is **not** a live Minecraft PASS. The alpha version remains `0.1.0-alpha.7`.

## Implementation and headless/build evidence

| Acceptance area | Current status | Evidence / boundary |
|---|---|---|
| SPH pool ↔ block collision | **Implemented; headless verified** | Java samples Minecraft `VoxelShape` AABBs; native SPH projects particle spheres against up to 512 static AABBs. `fluid_world::external_block_aabb_displaces_fluid_particles` and API fluid smoke pass. Live Minecraft sampling remains untested. |
| SPH ↔ rigid bodies, displacement and buoyancy | **Implemented; headless verified** | Up to 64 rigid AABBs per pool step; SPH returns contact reaction impulses; hydrostatic AABB overlap adds Archimedes impulses. Native tests cover equal/opposite contact, pool displacement and buoyancy. Live motion remains untested. |
| Soft body ↔ blocks and rigid bodies | **Implemented; headless verified** | XPBD projects 2.5 cm particle spheres against sampled block/rigid AABBs, retaining contacts through structural solve. Dynamic contacts return reaction impulses to the rigid worker. Rust contact/coupling tests pass. Live interaction remains untested. |
| Soft-body self-collision | **Implemented; headless verified** | Spatial-grid vertex self-collision excludes active structural neighbours; scratch buffers are reused and unchanged collision-free states are cached. Rust tests cover separation and cache invalidation. |
| Soft/rigid coupling and attachment | **Partial by design; headless verified** | Physical particle/AABB contacts send reaction impulses to the rigid solver. Explicit attached layers remain one-way kinematic constraints; attachment/wind smoke covers transform, target update and release, not a live server. |
| Cloth/rope tearing | **Implemented; headless verified** | Strain thresholds remove overloaded distance/volume constraints; the cached boundary-triangle surface is rebuilt and resent after tearing. Rubber/jelly do not tear. Rust tearing/surface test passes. |
| Multi-loader source layout and NeoForge | **Built and packaged** | Root Gradle build passed for `common`, `fabric`, and `neoforge`; JDK 21, Gradle 8.10.2. Both loader jars embed the platform-neutral `:common` output. Each Gradle check inspects the distributable and loads `NativeLoader`/`NativeBridge` with an isolated archive classloader. NeoForge uses ModDevGradle and is not a stub. No NeoForge client/server was launched. |
| Sable Companion packaging | **Verified in produced archive** | NeoForge jar contains `META-INF/jarjar/metadata.json` and `sable-companion-common-1.21.1-1.6.0.jar`, with range `[1.6.0,)`. Fabric jar contains its Fabric-compatible nested companion jar. |
| SPH performance (<15 ms / 320; <30 ms / 600) | **Passed in isolated release benchmark** | 10 warm-up + 100 measured requested 0.05 s ticks; 320 particles: **11.5 ms/tick, 11 substeps**. 600 particles: **28.5 ms/tick, 14 substeps**. No external block/rigid colliders, chunk scanning, packets, rendering or other mods; one sandbox run, hardware-dependent. 600 has limited headroom. |
| XPBD performance (<1 ms / 1,000 / 4 iterations) | **Passed in isolated release fixtures** | 1,000-particle tetrahedral mesh, 4 iterations, self-collision enabled: **0.305 ms/frame** at rest and **0.805 ms/frame** for a distributed active-deformation fixture in a repeat run (also 0.748 ms for a local active fixture). One 1/120 s substep, no block/rigid colliders or game overhead. |
| Public API expansion | **Implemented; headless API smokes pass on both mappings** | Force/torque pulses, angular-velocity setter, block-state lookup, density registration, block/block-entity force providers, and immutable client snapshots for rigid blocks, fluid pools and soft meshes are present. Fabric and NeoForge API smoke tests report `API_SMOKE_OK`. |
| Current alpha.7 distributables | **Rebuilt, inspected and checksummed after packaging fix** | Updated Fabric/NeoForge jars and source jars are in `release/alpha.7/`; all five SHA-256 entries pass. Both mod jars contain all `:common` classes including `NativeLoader`, `NativeBridge`, and `Protocol`, plus Linux `.so` and Windows `.dll`; NeoForge Jar-in-Jar metadata remains present. Gradle archive checks and isolated-classloader payload smoke pass for both jars. Linux native SHA-256: `67ce8f365d97bf2cfa9a80d0f54c045c697bd35a0fa1489cd8b5b937fe6b4ae9`; Windows DLL SHA-256: `11d258318cfa2ba309da1fe5cb907ecfb883bc0a74c704074ade76afa23491f2`. Windows DLL runtime has not been tested. |
| Full Rust quality checks | **Passed** | `cargo test --locked`: 131 passed, 0 failed, 5 ignored. `cargo clippy --locked --all-targets -- -D warnings`: passed. Release SPH, stationary XPBD, and active XPBD benchmark tests pass. |
| Live Minecraft acceptance | **Partially attempted; blocked before the fix, awaiting retest** | User launched Fabric on Windows 11 / Minecraft 1.21.1. The world started, but first Honey Glue use crashed the integrated server with `NoClassDefFoundError: dev/foxphys/nativecore/NativeLoader`. Inspection confirmed alpha.7's Fabric jar omitted every `:common` class: `implementation project(':common')` supplied compile/runtime classes but did not package them. Both loader jars now embed `:common`, and packaged-class checks pass. The corrected Windows jar has not yet been run by the user; native DLL runtime and all gameplay acceptance remain unverified. No NeoForge client/server was launched. |

## Live checks still required before calling alpha.7 closed

Run on both Fabric and NeoForge in disposable worlds; record jar hashes, client/server logs, screenshots/video and MSPT/TPS. Most criteria are still **NOT TESTED**. Re-test the corrected Fabric jar first: delete any older `foxphys-fabric-*.jar` copies from `mods`, install exactly one corrected jar, and use a backed-up/disposable world. The initial user run reached a world but first Honey Glue use failed on the missing common class; no gameplay physics passed yet.

- Rigid-body rendering/interpolation, player standing/walking/crouching/camera stability and being carried by a moving body.
- SPH display, water-bucket block/air/Shift click modes, block/rigid displacement and plausible buoyancy under live ticking.
- Soft-body display/interpolation, block and rigid contacts, self-collision, two-way contact response, attachment and cloth/rope tearing.
- Gravity-gun grab/release, launch direction and `R` alignment.
- Explosion impulse behaviour and world-block integrity.
- Sodium/Iris/shader compatibility and real server load under representative scenes.
- Windows native DLL runtime on Windows.

Use [`IN_GAME_TEST_CHECKLIST.md`](IN_GAME_TEST_CHECKLIST.md). Do not mark the specification fully closed until these observations have been recorded; headless checks and jar inspection are not substitutes.

## Reproduction commands

```bash
cd native
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --release --lib bench_tick -- --ignored --nocapture
cargo test --locked --release --lib soft_budget_1000_particles -- --ignored --nocapture
cargo test --locked --release --lib soft_active_budget_1000_particles -- --ignored --nocapture
```

From the repository root, the final JDK 21 / Gradle 8.10.2 build (`:fabric:build :neoforge:build`) completed successfully with one worker. Fabric JNI/protocol tasks reported `JNI_SMOKE_OK` and `PROTOCOL_SMOKE_OK`; both loader checks verified and loaded the packaged JNI classes in isolation. `validation/packaging/PackagedLoaderSmoke.java` also reported `PACKAGED_LOADER_SMOKE_OK` for both release jars. The four standalone API/fluid/attachment scripts reported `API_SMOKE_OK` on both mappings, `FLUID_SMOKE_OK`, and `ATTACH_SMOKE_OK` in the previous validation run.

## Disk scan and safe cleanup

Earlier, `/home/user/voxphys` was about **1.1 GB** and `/home/user/.gradle` about **937 MB**; only generated Rust/Gradle caches and disposable build outputs were removed after saving releases. Sources, smoke tests, Cargo lockfile, staged natives, `release/alpha.7/`, and the protected `neoforge/run/` world (**4.8 MB**) were preserved. The packaging fix rebuilt the release, then the workspace reported **1,036.9 MB / 3,340 files** against a **128 MB** snapshot budget. Only confirmed generated Gradle caches/build directories were removed; no source, tests, staged native libraries, release artifacts, or game world were touched. Current workspace is **43 MB / 477 files** (`/home/user/voxphys` 33 MB plus the separate `/home/user/release` 9.9 MB). No `.gradle`, `build`, or `target` directories remain. `release/alpha.7/` (16 MB), `neoforge/run/` (4.8 MB), and `neoforge/prebuilt/` (7.8 MB) are preserved, and all five release checksums pass. Current disk use is **4.1 GB / 21 GB free of 25 GB**.
