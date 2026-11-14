# Current limitations and verification scope

Build success and headless tests confirm compilation and selected code paths; they do **not** prove that client rendering, player interaction, multiplayer behaviour or end-to-end server performance works in a real game.

## Water and soft-body stability

Water and soft bodies are **extremely unstable experimental features**, not production-ready systems. Numerical checks cover selected fixtures only; they do not establish reliable behaviour over long sessions or in a live client. Expect solver/visual artifacts and potentially high CPU cost. Use a disposable world and remove the feature if it destabilizes the session. See [water](WATER.md) and [soft bodies](SOFT.md).

## Game verification

The Minecraft client has **not** been launched for a full gameplay check. These remain unconfirmed in-world on Fabric and NeoForge:

- soft-body, SPH water and rigid display rendering/interpolation;
- SPH block/rigid collisions, buoyancy and water-bucket click modes;
- soft-body block/rigid/self contacts and cloth/rope tearing;
- player standing, crouching, camera and movement on a moving body;
- gravity-gun aiming, grab/launch directions and the **R** alignment key;
- Sodium/Iris/shader interactions on Fabric;
- network behaviour under latency and multiple players.

Use a disposable test world and the [in-game checklist](IN_GAME_TEST_CHECKLIST.md). A headless smoke test cannot close any of these items.

## Rigid bodies and world collision

- FoxPhys supplements, but does not replace, vanilla entity or fluid physics.
- The world is sampled around active bodies from Minecraft collision `VoxelShape`s. Terrain updates are incremental; unloaded chunks, geometry outside the sampled region and regions beyond the scan budget cannot provide colliders.
- An environment scan visits at most 60,000 unique block positions and stores at most 16,384 terrain boxes. Scan reach is 128 m around the physics anchor. Raising `environment_scan_interval` reduces scan frequency but widens predictive scan margins and delays world-edit updates.
- The native world has a maximum of 8 simultaneous handles. Configuration permits `max_bodies` from 0 through 10,000; zero disables only the body-count cap. Assembly guards are 4,096 cells per operation and 8,192 physical block cells per world. A body is capped at 512 collider parts; assemblies above that are refused rather than silently truncated.
- Collision geometry is represented by axis-aligned boxes. Context-sensitive block geometry, exact triangle meshes and terrain deformation are unsupported; complex shapes may be simplified if they exceed the per-shape box limit.
- Density-derived mass/inertia is an estimate, not an authoritative Minecraft gameplay property. Registering a new density does not recalculate existing bodies.
- Explosion impulses use distance falloff and a force cap, but do not model block shielding/exposure. They are approximate and have not been confirmed in a live game.

## Assemblies and saving

- Attaching cells to a body and disassembling require grid-compatible orientation (multiples of 90°). Arbitrarily tilted assemblies are refused rather than approximated as blocks.
- Block states are restored using block identifiers; all state properties (for example, stair orientation) are not guaranteed to survive an assembly/save cycle.
- Rigid bodies, springs, joints and wind are saved. Terrain is re-read from Minecraft. Soft bodies and SPH pools are not included in the saved state.
- The 512 limit is a collider/part limit, not a general polygon budget. Mesh coalescing reduces the number of parts where possible; checkerboard-like assemblies can still hit the cap.

## Soft bodies

- Soft bodies now test against sampled static block AABBs, nearby rigid-body AABBs, a floor plane and non-neighbour vertex self-collision. These are coarse sphere/AABB projections, not exact deformable mesh contacts.
- Rigid contact impulses are sent back to the rigid solver. The separate explicit attachment constraint remains one-way (the anchored layer follows the rigid body).
- Cloth/rope tearing removes overloaded constraints and refreshes the surface triangles; it is not realistic fracture and does not duplicate vertices.
- Presets are limited to 16 bodies and 5,000 particles per body; each environment is capped at 512 static boxes and 64 rigid bodies. The native 1,000-particle/four-iteration test is below 1 ms per 1/120-second substep (about 0.8 ms for the active deformation fixture with self-collision), but excludes block/rigid contacts and the game's 8 substeps per tick. It does not represent a complete Minecraft tick or multiple bodies.
- Soft-body visibility and behaviour remain unverified in the live game. The renderer logs `FoxPhys soft body #<id>` with particle count, triangle-index count and bounds.

## Water

- The shallow-water reservoir is 2.5D over a flat bottom, without vertical flow, overturning, waterfalls or a fully coupled rigid-body reaction.
- SPH pools now project particles against nearby block AABBs and rigid-body AABBs, return dynamic contact impulses, and compute approximate hydrostatic buoyancy. This is not full two-way CFD; collision and buoyancy are AABB approximations.
- Pools are fixed-size/open-top, capped at 1,024 fluid particles natively and 8 active pools in the game integration. A step is capped at 60 substeps; overload means slower simulated water, not an unbounded catch-up loop.
- The release CPU benchmark measures a single pool without external colliders: 320 particles about 11.6 ms and 600 particles about 28.3 ms per requested 0.05 s on one recorded run. The larger case has limited margin under 30 ms and is not an end-to-end Minecraft server tick profile.
- Rendering is particle cubes rather than a reconstructed liquid surface. There is no foam, refraction, splash, bubbles, surface tension, pool merging or growth.

## Air, wind and visual effects

- Air/water force models are engineering approximations, not CFD; wakes, mutual shielding, boundary layers and fitted coefficients for each block are omitted.
- Weather wind varies with weather and time, but does not model height, shelter or airflow around terrain. `/foxphys wind x y z` can be overwritten by the automatic weather update on a later tick; persistent manual override is not implemented.
- Display entities and mixins are part of the rendering path; behaviour with a live client, graphics mods, shaders and resource packs still needs testing.

## Performance interpretation

A Java terrain-scan microbenchmark isolates collection/key allocation; it is **not** an end-to-end Minecraft tick/TPS profile. Native solver microbenchmarks exclude chunk access, entity updates, collider sampling (unless explicitly included), packet encoding, client rendering and other mods. The SPH/XPBD budgets are local CPU-harness measurements, not performance guarantees on every computer. See [Build and testing](BUILD_AND_TESTING.md).
