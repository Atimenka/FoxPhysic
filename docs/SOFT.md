# Soft bodies

Soft bodies are simulated in Rust with XPBD tetrahedral constraints. Java creates bodies, applies pins/anchors and wind, samples nearby Minecraft/rigid colliders, and sends positions and surface triangles to clients. Cloth and rope presets can tear; tearing removes overloaded constraints and refreshes the exported surface topology.

> **Stability warning:** soft bodies are extremely unstable experimental features and must be treated as unstable in gameplay. Headless tests cover selected numerical cases only; they do not establish reliable long-running behaviour, attachments under arbitrary motion, correct live rendering, or predictable server load. Expect solver/render artifacts and test only in a disposable world.

## Commands

The commands work on both loaders. `/fox` is an alias for `/foxphys`.

| Command | Behaviour |
|---|---|
| `/foxphys soft spawn rubber|jelly|cloth|rope` | Create a preset about 2.5 blocks east of the player, at foot level. |
| `/foxphys soft status [id]` | List bodies or report one body's centre, maximum particle speed, and volume-ratio range. |
| `/foxphys soft pin <id> top|bottom|start|end` | Pin the particles on a side; returns the number pinned. |
| `/foxphys soft stiffness <id> <k>` | Scale distance/volume compliance. `k` is 0.05–100; 1.0 is the preset value. |
| `/foxphys soft damping <id> <d>` | Set damping in 1/s; range 0–50. |
| `/foxphys soft attach <id> <side> [bodyId]` | Attach one particle layer to a nearby rigid body, or to `bodyId`. |
| `/foxphys soft detach <id>` | Release the attached layer. |
| `/foxphys soft remove <id>` | Remove the body. |

`<side>` is `top`, `bottom`, `start`, or `end`. Bodies can also be manipulated through `SoftBodyApi`; see [API reference](API.md).

## Presets and limits

Nominal particle spacing is 5 cm. Grid counts include the boundary nodes used to construct the tetrahedral mesh.

| Preset | Grid | Particles | Density (kg/m³) | Distance / volume compliance |
|---|---:|---:|---:|---:|
| rubber | 4×4×4 | 125 | 1100 | 5×10⁻⁶ m/N / 1×10⁻⁹ m³/N |
| jelly | 6×6×6 | 343 | 1000 | 5×10⁻⁶ m/N / 1×10⁻⁹ m³/N |
| cloth | 16×1×16 | 578 | 800 | 1×10⁻³ m/N / 1×10⁻⁶ m³/N; a 5 cm sheet, not a fabric constitutive model |
| rope | 24×1×1 | 100 | 1100 | 1×10⁻⁴ m/N / 1×10⁻⁷ m³/N |

Limits: 16 active bodies, 5,000 particles per body, 512 static AABB boxes and 64 nearby rigid-body AABBs per soft-body environment. The server advances once per game tick using 8 substeps and 4 constraint iterations. Density is preset data, not derived from the block-density table.

## Solver and collisions

- XPBD distance constraints and tetrahedral-volume constraints use compliance scaled by the squared substep duration. Damping is applied to particle velocities.
- Each particle is represented by a 2.5 cm-radius sphere for external and self contacts.
- Nearby Minecraft `VoxelShape`s are sampled into AABBs each tick (within a 1 m margin); moving FoxPhys rigid bodies contribute dynamic AABBs with their current velocity and mass. Contacts are projected between XPBD iterations so the structural solve does not immediately restore penetration.
- Dynamic rigid contacts return equal-and-opposite impulses to the rigid-body worker. This is an AABB approximation, not a unified deformable/rigid solver.
- Self-collision uses a spatial grid and inelastic particle response. Active structural-edge neighbours are excluded so the mesh does not fight its own constraints. The broadphase buffers are reused; an exact unchanged, collision-free configuration can reuse the prior no-contact result.
- An optional floor plane is also available. `pin` is an immovable solver constraint, not an anchor to a Minecraft block.

`status` reports finite-state information, centre, maximum speed, stiffness, damping, particle/constraint counts, tearing count and minimum/maximum volume ratio. A volume ratio below 1 means compression; it is not a measure of visual quality.

## Tearing

Only cloth and rope presets have strain thresholds (cloth `1.8`, rope `2.0`). When an edge length reaches its threshold relative to its rest length, the solver removes that structural edge and the volume constraints that depend on it. The boundary-triangle surface is then rebuilt, and the next publish forces a fresh topology to clients.

This is intentionally a simple constraint-tearing model: it does not duplicate vertices, create fracture energy, model fibers, or guarantee a physically realistic tear path. Rubber and jelly do not tear in alpha.7.

## Attachment to rigid bodies

An attached particle layer follows a rigid body as a flag follows a pole or a rope end follows an assembly:

1. Java records each selected particle's offset in the rigid body's local frame.
2. Before each soft-body step, Java transforms those offsets by the body's current position and rotation and submits target coordinates.
3. Rust holds the anchored particles at those targets and restores their saved inverse mass when anchors are released.

This **attachment constraint remains one-way**: the chosen layer follows the rigid body but does not push it through the anchor. Separately, physical soft-particle contacts with nearby dynamic rigid AABBs do return reaction impulses. Attachments are removed when their rigid body disappears or is disassembled. The headless attachment smoke covers rotation, target updates, release and wind; it does not test the in-game interaction loop.

## Wind

Soft particles receive a per-particle drag approximation based on `u = wind − particleVelocity`. The nominal particle diameter is 0.05 m and the force coefficient is `0.5 × 1.2 × 1.0 × diameter²`. Rigid bodies receive weather-derived wind through the rigid-body force system.

Weather changes the approximate base speed (calm ≈ 1.5 m/s, rain ≈ 5.5 m/s, thunderstorm ≈ 13.5 m/s); direction drifts slowly and gusts vary with game time. Height, shelter and terrain do not modify airflow. The manual `/foxphys wind` vector may be replaced by automatic weather on a later tick.

## Network and rendering

- The server updates particle positions every tick and sends a `soft_meshes` payload to clients.
- The surface triangles are generated from the tetrahedral topology and cached. Full topology is sent when a body appears and periodically (every 20 ticks); after tearing it is sent again immediately. Intervening packets can omit the indices while sending new positions.
- The client interpolates between server updates and draws cached triangles. Both loaders exempt FoxPhys display entities from stale frustum culling; Fabric has optional Sodium-specific render mixins.
- If a body is not visible, look for `FoxPhys soft body #<id>` in the client log. It reports particles, triangle indices and bounds. `soft body view failed` on the server indicates a send failure; recovery is retried and logged.

The rendering path compiles and has headless protocol checks, but actual visibility/interpolation has **not** been confirmed in a launched Minecraft client. See the [in-game checklist](IN_GAME_TEST_CHECKLIST.md).

## Performance reference

The ignored native benchmarks can be run with:

```bash
cd native
cargo test --locked --release --lib soft_budget_1000_particles -- --ignored --nocapture
cargo test --locked --release --lib soft_active_budget_1000_particles -- --ignored --nocapture
```

Both use 1,000 particles, 4 XPBD iterations and a single 1/120-second substep per measured frame. The stationary rest-shape workload reported **0.305 ms/frame** with self-collision enabled. The active workload warms for 25 frames, then measures 500 frames after either a 1 cm local particle displacement or a small distributed deformation; a repeat run reported **0.748 ms/frame** (local) and **0.805 ms/frame** (distributed), with self-collision enabled. It includes no external block/rigid colliders. The solver uses compact 16-bit mesh indices, a rescaled squared-distance constraint, and a sub-micrometre residual cutoff for converged XPBD corrections.

These are native CPU-harness measurements, not complete game-tick budgets: Minecraft, networking, rendering, collider sampling, other bodies and the game's 8 substeps per tick are excluded. Costs grow with active bodies and contacts. A more pathological deformation can cost more; use the tests as reference points, not an end-to-end TPS guarantee.

## Limitations

- Soft bodies and water are extremely unstable experimental features; use a disposable world.
- Contacts use AABBs derived from block collision shapes and rigid-body bounds. Thin/complex shapes, many colliders, and fast motions can still produce artifacts.
- The self-collision model is vertex-based and inelastic; it is not triangle/triangle collision and can allow surface-level overlap.
- Attachments are one-way kinematic constraints; only physical particle contacts send reaction impulses to rigid bodies.
- Tearing is a simple threshold-based constraint removal and topology refresh, not realistic fracture.
- Visual behaviour and performance in a live client/server remain unverified.
