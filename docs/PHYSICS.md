# Physics models and units

## Units and coordinates

- Length: metre; 1 block = 1 m.
- Time: second; a Minecraft tick = 0.05 s of logical time.
- Mass: kg; density: kg/m³; force: N; impulse: N·s.
- Y points up; gravity `(0, -9.81, 0)` m/s² by default.
- Rust uses coordinates relative to the integer anchor of the dimension-scoped physics world. This reduces `f32` precision loss far from the anchor in the Minecraft world.
- The quaternion in the protocol is `[x, y, z, w]`; the orientation is normalised when a body is added.

## Rigid bodies

Rapier 0.22 solves dynamics, contacts, rotation, friction, and CCD. Each element of a structure is a separate cuboid collider with a density. Mass and the inertia tensor are derived from the colliders; different materials are possible in one body. Overlapping elements are rejected so that mass and volume are not counted twice.

The default fixed step is `Δt = 1/120 s`. Collisions with Minecraft are read from `VoxelShape`, not only from full cubes. Context-dependent collision shapes, exact triangle meshes, and deformation are not supported.

## Explosion impulses

Server-side Minecraft explosions queue one batched impulse request for each active rigid body whose AABB intersects the blast radius. The radius is `2 × explosion power`, capped at 128 m. Distance is measured from the explosion centre to the nearest point on the body's AABB; the target velocity change is `min(8 m/s, 0.75 × power × (1 − distance/radius))`. The impulse is `mass × Δv`, capped at `1,000,000 N·s`, and is applied at the nearest AABB point so an off-centre blast can add rotation. The request is queued asynchronously rather than waiting on the server thread.

This is an intentionally bounded gameplay approximation, not a vanilla explosion calculation for rigid bodies. It does not sample block shielding or explosion exposure, so an intervening wall does not reliably protect a physics body. The assembled physics-block cells remain part of the body; the blast changes the body's motion, not its ownership or block contents. Test the interaction in a disposable world; live-client behaviour has not been verified.

## Buoyancy

`F_b = ρ_water g V_submerged`, directed upwards. Each cuboid gets a 4×4×4 grid of volume sample points. For each point, the fraction of its vertical interval inside the water is counted; the force is applied at the corresponding point, so a moment about the centre of mass arises.

For axis-aligned cubes with a flat surface, the submerged volume is exact within floating-point error. For rotated cubes this is a **quadrature approximation**, not an exact intersection of polyhedra with the surface. The number 64 is not a proof of high accuracy in every pose.

Bodies do not exclude their volume from the water solver. Sealed cavities, trapped air, flooding, surface-tension adhesion, and cavitation are not implemented yet.

## Air and water drag

The open faces of cuboids are split into 2×2 panels. Faces fully covered by a neighbouring cuboid are excluded; partially covered surfaces are not clipped yet.

For relative flow `u_rel = u_medium - v_point`, the windward face receives:

`F_pressure = -n · ½ ρ C_n A · min(u_rel·n, 0)²`.

The tangential component:

`F_shear = ½ ρ C_f A |u_t| u_t`.

Submerged panels use the water density and velocity; dry panels use air and wind. This is a local engineering model: it does not compute the surrounding pressure field, the wake, mutual shielding between bodies, or the viscous boundary layer. The coefficients are not experimentally calibrated for each Minecraft block.

Large explicit drag impulses are smoothed by a rational limiter `F / (1 + |F| Δt / (m_share |u_rel|))`. This is a step stabiliser that changes the exact drag law at high speeds and small masses.

For an explicitly declared wing, the model uses a linear slope `C_L ≈ 2π α`, an empirical blend with a flat-plate model between 15° and 25°, and `C_D = C_D0 + C_L²/(π AR e) + C_D_stall`. This is a symmetric approximate model, not a specific NACA profile; compressibility and realistic turbulence are absent. For a wing, the air face model is switched off so that the same forces are not counted twice.

## Wind (weather-dependent, gusts)

Wind is set by `Wind.java` (both builds) and sent to Rust with `set_wind`. Base speed follows the weather: calm ≈ 1.5 m/s, rain ≈ 5.5 m/s, thunderstorm ≈ 13.5 m/s. The direction drifts slowly, and gusts are a sum of sines over game time. The `/foxphys wind x y z` command also sends `set_wind`, but `LabManager` recomputes and reapplies weather-driven wind every server tick; a persistent manual override or additive wind is not implemented. The command's in-game effect was not tested. Wind does not depend on height or on blocks. Details: `docs/SOFT.md`.

## Water: the current solver (reservoir, 2.5D)

Over a flat bottom, the solver stores the depth `h` and the depth-integrated momenta `hu`, `hv` along X/Z:

```text
∂h/∂t + ∂(hu)/∂x + ∂(hv)/∂z = 0
∂(hu)/∂t + ∂(hu²/h + g h²/2)/∂x + ∂(huv/h)/∂z = -g n² |u| u / h^{1/3} - ν-diffusion
∂(hv)/∂t + ∂(huv/h)/∂x + ∂(hv²/h + g h²/2)/∂z = (similarly)
```

Current numerical scheme:

- finite-volume method; reconstruction of the primitives `(h, u, v)` with a minmod limiter (second order in smooth regions, first order at walls and in dry cells);
- Rusanov flux on the reconstructed states;
- SSP-RK2 (Heun) in time, with an adaptive step from the CFL condition `0.4 dx / (|u|+|v|+2√(gh))`;
- reflecting walls: the normal momentum is mirrored, so the mass flux through a wall is **exactly zero**;
- Manning friction `roughness` (default 0 — off): semi-implicit in velocity, `u ← u / (1 + Δt g n² |u| / h^{4/3})`;
- optional `damping` and momentum viscous diffusion `viscosity`; `roughness` defaults to zero.

Mass (`Σh·dx²`) is conserved to rounding (`~10⁻¹⁵` in tests). Buffers are allocated once when the reservoir is created. Depth and momentum are computed in `f64`; Rapier and snapshots use `f32`.

### What was checked for accuracy

| Test | Result |
|---|---|
| Still water | stays still (`|u| < 10⁻¹²`) |
| Seiche (first mode of a closed basin, linear theory `T = 2L/c`) | period matches the theory within 0.05 %; 84 % of the amplitude is kept after 9 periods (first order — 11 %) |
| Dam break on a dry bed (Ritter 1892), 5 cm contour | error −0.20 m at 0.4 s (first order: −0.04 m) |
| Manning friction | at `n = 0.1` the front is noticeably slower; mass is conserved |

The honest conclusion: the second order is **noticeably better on waves** (less numerical diffusion), but **worse at a sharp dry front** — its tip is smeared over about 2 cells. This is a known limitation; an improvement needs a more accurate front solver (for example HLLC with a positivity check) and a separate convergence test.

### Limitations

The model applies to long surface waves in shallow water. The hydrostatic assumption excludes vertical dynamics. There are no 3D obstacles inside the liquid, no overturning waves, splashes, waterfalls, water columns, or caves. Water is not displaced by bodies, so there is no two-way momentum exchange "water ↔ body".

The reservoir has a physical floor and walls for Rapier, but it does not turn world walls into real geometry. The visual walls and water are vanilla display entities, so a player can walk through them.

## Water: SPH pools (3D)

A separate solver (`native/src/sph.rs`, see `docs/WATER.md`) runs volumetric pools: weakly compressible SPH (WCSPH) with the Tait equation of state. It is used by the physics water bucket and by `/foxphys water sph`. The game integration supplies nearby block-collision AABBs and dynamic rigid-body AABBs; particles are projected against both, dynamic contacts return reaction impulses, and an approximate hydrostatic Archimedes impulse supplies buoyancy. These are AABB-based interactions, not full two-way CFD/FSI. The shallow-water reservoir above remains a separate 2.5D model. Limits, tests and performance scope are described in `docs/WATER.md`.

## Block assembler: geometry and limits

A cell with integer coordinate `c` occupies the cube `[c, c+1]³`.

- **Merging into boxes** — greedy meshing by material. The coverage is **exact**: no overlaps, gaps, or extra volume. Tests check exact coverage for random shapes; a solid 8³ cube gives one box. Individual boxes longer than 16 blocks are split, because the protocol requires it.
- **Assembly** — the body is created at the centroid of the cells; part offsets are given relative to the origin of the body. Mass and inertia are derived by Rapier from the densities. A density of 800 kg/m³ for 4³ gives a mass of `800 · 64`.
- **Attaching** — cells are converted to the body's local coordinates `R^T(c − p)` and added as new colliders. Overlap with existing parts is rejected. All new parts are validated before the physical scene is changed.
- **Disassembly** — parts are converted back to the world grid. If the body is not aligned to the grid or the coordinates do not fall on integers, disassembly is refused and the body is kept.
- **Tilted bodies** (not multiples of 90°) cannot be attached or disassembled: a single cell does not give an exact box in the body's local frame. This is a deliberate boundary, not a bug.

Limits: 4096 cells per command, bounding box no larger than 64³, no more than 512 colliders per body and 512 colliders across the native world, coordinates `|c| ≤ 256`.

High-part-count setup uses an X-axis sweep for overlap validation and axis/plane indexes for exposed-face matching instead of scanning every part pair for each face. These reduce assembly/setup work; per-step physics still costs more as collider count grows. The 512-part cap is a safety ceiling, not a recommended target for every body.

A neural collision layer is not implemented. Assembly uses a deterministic exact greedy box cover; no learned collision model is involved.

## Springs and joints

For attachment points A/B and the direction `n = (p_A-p_B)/L`:

`F_B = n [ k(L-L₀) - c(v_B-v_A)·n ]`, `F_A = -F_B`.

Forces are applied at the attachment points, not necessarily at the centres of mass. Attachment to the world and a link between two bodies are supported. Springs with too large `k Δt² / m_eff` or `c Δt / m_eff` are rejected; this is a rough translational stability estimate, not a guarantee for arbitrary long lever arms and rotation. The solver also supports hinge, slider, cone-twist, and rope joints, including force-triggered breaking. Gears and motors are not implemented.

## Scope of the realism claim

The physical quantities and laws are stated explicitly and checked by tests. But this is **not a scientifically validated CFD/FSI system**, not a full replacement for the game's physics, and not a promise of "super-realism". Any improvement of the model must come with convergence tests, error measurements, energy-balance checks, and a time-per-step measurement.
