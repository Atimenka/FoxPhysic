# FoxPhysic (FoxPhys)

**Minecraft 1.21.1 · Fabric and NeoForge · Java 21 + Rust native physics**

Current source version: **0.1.1-beta-release**. This source bundle is a **beta candidate**, not a completed/validated release: build the Fabric and NeoForge jars with the commands in `docs/RELEASE_0.1.1_BETA.md`, then complete the live checks before publishing. FoxPhys adds experimental rigid-body physics, XPBD soft bodies, two separate water solvers, block assembly tools, and a public API for other mods. Rust performs the simulation; Java connects it to Minecraft, commands, networking, and rendering.

FoxPhys does **not** replace vanilla player, mob, or vanilla-fluid physics. Its commands require operator permission level 2. Use a separate test world and back it up before using the assembler or glue.

> **Stability warning:** water and soft bodies are extremely unstable experimental features. Their headless checks cover selected fixtures only; they do not establish reliable gameplay or long-running stability. Use a disposable world and expect performance/visual artifacts.
>
> **Verification status:** the 0.1.1-beta-release source has not yet been built or tested. Its code is based on alpha.7; that version's JDK 21 Fabric/NeoForge builds, JNI/protocol checks, API/fluid/attachment headless smokes, Rust tests and performance fixtures passed. The alpha.7 packaging omission for `NativeLoader` was fixed and its rebuilt jars passed isolated-classloader checks. The corrected jar still awaits the user's Windows live retest; Windows DLL runtime, NeoForge client/server and in-game physics criteria remain unverified. Water and soft bodies are extremely unstable. Do not call this beta verified until the new version is built and live checks pass. See [known limitations](docs/KNOWN_LIMITATIONS.md), [beta build notes](docs/RELEASE_0.1.1_BETA.md), and the [in-game checklist](docs/IN_GAME_TEST_CHECKLIST.md).

## Install

| Loader | Required environment | Artifact |
|---|---|---|
| Fabric | Minecraft 1.21.1, Fabric Loader 0.16.14+, Fabric API 0.116.6+1.21.1, Java 21 | `fabric/build/libs/foxphys-fabric-0.1.1-beta-release.jar` after build |
| NeoForge | Minecraft 1.21.1, NeoForge 21.1.259+, Java 21 | `neoforge/build/libs/foxphys-neoforge-0.1.1-beta-release.jar` after build |

The source archive does not include newly built beta binaries. Build both loaders first; then install the matching jar on both client and server. Fabric additionally requires Fabric API. The build packages native libraries for Linux x64 and Windows x64; players do not need Rust or Visual Studio. The Fabric jar bundles Sable Companion as an optional nested jar; NeoForge uses Jar-in-Jar for its common artifact.

For alpha.7, the prior release artifacts and checksums remain in `release/alpha.7/`; do not use those files when testing this beta candidate.

The mod creates `config/foxphys.json` on first launch. Settings are read at startup; restart after changing them. Defaults, valid ranges, and each setting's effect are documented in [Runtime configuration](docs/BUILD_AND_TESTING.md#runtime-configuration).

## Features

- **Rigid bodies:** Rapier 3D dynamics, compound cuboid colliders, mass and inertia from density, friction, joints, contacts, and continuous collision detection.
- **Minecraft terrain:** collision boxes sampled from Minecraft block `VoxelShape`s around active bodies; terrain is synchronized incrementally.
- **Forces:** gravity, buoyancy, water/air drag, approximate aerodynamics, weather-driven gusting wind, and distance-weighted blast impulses for physical bodies.
- **Mechanics:** springs and hinge, slider, cone-twist, and rope joints; links may break at a configured force.
- **Block tools:** assemble blocks into bodies and disassemble them; gravity gun, grid alignment, and honey glue.
- **Water:** a 2.5D shallow-water reservoir and separate 3D WCSPH particle pools; SPH pools project against block/rigid AABBs and return approximate contact/buoyancy impulses. The debug water bucket creates pools.
- **Soft bodies:** rubber, jelly, cloth, and rope presets; block/rigid contacts, vertex self-collision, wind, one-way layer attachment and cloth/rope tearing. Contact response pushes back on rigid bodies.
- **Public API:** `dev.foxphys.api` with API version 1 for densities, rigid bodies, soft bodies, and events.
- **Block properties:** a 1060-block table of in-game hardness/blast resistance and estimated density/fragility. The estimates are not vanilla Minecraft properties.

Technical references: [API](docs/API.md) · [Physics models](docs/PHYSICS.md) · [JNI protocol](docs/PROTOCOL.md) · [Soft bodies](docs/SOFT.md) · [Water](docs/WATER.md) · [Block properties](docs/BLOCK_PHYSICS.md).

## Commands

`/fox` is a short alias for `/foxphys`; `/voxphys` is retained as a compatibility alias. Commands are operator-only.

```text
/foxphys                              show help
/foxphys spawn wood|stone|boat|wing   spawn a test body
/foxphys impulse <id> x y z           apply an impulse in N·s
/foxphys spring                       create a spring test
/foxphys wind x y z                   set wind in m/s (weather may replace it next tick)
/foxphys water still|dam              create a 2.5D reservoir
/foxphys water sph still|dam|clear    create or remove 3D SPH pools
/foxphys pause true|false             pause or resume rigid-body simulation
/foxphys status                       show world and physics status
/foxphys save | restore | clear       save, restore, or clear rigid-body state
/foxphys assemble <N>                 assemble an N×N×N block cube in front of the player
/foxphys disassemble <id>             return an assembled body's blocks to the world
/foxphys assembler give | run ...     give/use the assembler tool
/foxphys gun give                     give the gravity gun
/foxphys glue give                    give honey glue
/foxphys soft spawn rubber|jelly|cloth|rope
/foxphys soft status [id] | remove <id>
/foxphys soft pin <id> top|bottom|start|end
/foxphys soft stiffness <id> <k> | damping <id> <d>
/foxphys soft attach <id> top|bottom|start|end [bodyId]
/foxphys soft detach <id>
/foxphys api                          show API and native-library status
```

Body IDs are printed in chat. Coordinates and dimensions use metres; **one block = one metre**. A 1 m³ wooden cube has a mass of about 550 kg, so a small impulse may barely move it.

**Gravity gun:** right-click grabs/releases; left-click launches; press **R** while holding a body to align it to the block grid.

**Physics water bucket:** right-click a block to create a pool at the clicked face; Shift+right-click starts a dam break; right-click air to place a pool near the player. It is a debug item, not vanilla water.

## Safety and configuration

- Use a throwaway Creative world for initial tests. The assembler and honey glue can remove world blocks and convert them into physics bodies.
- `max_bodies` defaults to 64. `max_bodies = 0` removes that body-count cap; assembly still has a separate world limit of 8192 physical block cells.
- Terrain scanning is limited to 60,000 unique block positions per scan and 16,384 terrain colliders. Large or distant scenes may therefore be only partially represented.
- A physics world is created on first use for a dimension; there is no fixed laboratory stand. Physics pauses when no player is near the world/bodies.
- Body, spring, joint, and wind state can be saved. Terrain is sampled again from the world; SPH pools and soft bodies are not part of that saved state.

## Build and test

See [Build and testing](docs/BUILD_AND_TESTING.md) for JDK/Rust requirements, memory settings, reproducible build commands for both loaders, native-library packaging, smoke tests, and output locations. The current headless checks and their scope are listed in [`validation/README.md`](validation/README.md).

## Documentation and data

- [Mod API for third-party mods](docs/API.md)
- [Physics models and units](docs/PHYSICS.md)
- [JNI and native JSON protocol](docs/PROTOCOL.md)
- [Soft-body controls, solver, and rendering](docs/SOFT.md)
- [Water solvers and limitations](docs/WATER.md)
- [Block property table](docs/BLOCK_PHYSICS.md) and [`block-physics.csv`](docs/block-physics.csv)
- [Current limitations](docs/KNOWN_LIMITATIONS.md)
- [In-game test checklist](docs/IN_GAME_TEST_CHECKLIST.md)
- [Build/test instructions](docs/BUILD_AND_TESTING.md)

## Licences

FoxPhys code is MIT. Rapier and Parry are Apache-2.0; bundled notices and third-party licences are in `THIRD_PARTY_NOTICES.md` and `licenses/`. Minecraft, Fabric API, and NeoForge are not bundled; install the appropriate loader dependencies separately.
