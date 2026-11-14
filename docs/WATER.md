# Water systems

FoxPhys contains two independent water simulations. They serve different purposes and do not exchange momentum with one another.

> **Stability warning:** water systems are extremely unstable experimental features, not production-ready fluid simulation. Headless checks cover selected numerical fixtures only; they do not establish reliable long-running gameplay, correct rendering, or predictable server load. Expect artifacts and test only in a disposable world.

| System | Model | Player interface | Main implementation |
|---|---|---|---|
| Shallow-water reservoir | 2.5D depth-averaged flow on a flat bottom; approximate rigid-body buoyancy/drag | `/foxphys water still|dam` | `native/src/water.rs`, `Lab` |
| SPH pool | 3D weakly-compressible SPH particles in a finite open-top box | `/foxphys water sph ...`, Physics Water Bucket | `native/src/sph.rs`, `native/src/fluid_world.rs`, `dev.foxphys.fluid` |

## 2.5D shallow-water reservoir

The reservoir evolves water depth `h` and depth-integrated horizontal momenta `hu`, `hv` on the X/Z plane:

```text
∂h/∂t + ∂(hu)/∂x + ∂(hv)/∂z = 0
∂(hu)/∂t + ∂(hu²/h + g h²/2)/∂x + ∂(huv/h)/∂z = friction + diffusion
∂(hv)/∂t + ∂(huv/h)/∂x + ∂(hv²/h)/∂z = friction + diffusion
```

Numerics: finite-volume reconstruction with a minmod limiter, Rusanov flux, SSP-RK2 integration, CFL-limited adaptive substeps, reflecting walls, optional Manning friction/damping/diffusion. Depth and momentum use `f64`; rigid-body snapshots use `f32`.

Water depth is conserved to floating-point tolerance in headless tests. Rigid bodies sample the reservoir for approximate buoyancy/drag, but the reservoir does **not** receive the equal-and-opposite body impulse. This hydrostatic model has no vertical flow, overturning waves, splashes, or 3D obstacles. It is independent of the SPH pools below.

## 3D SPH pools

The SPH implementation is weakly-compressible SPH (WCSPH):

- cubic-spline kernel and Tait equation of state;
- water rest density `ρ₀ = 1000 kg/m³`, artificial sound speed `c = 12 m/s`, gravity `9.81 m/s²`;
- Monaghan artificial viscosity (`α = 0.2`) and XSPH velocity smoothing (`0.5`);
- fixed boundary particles on the floor and four side walls; the top is open;
- particle mass `ρ₀·dx³`; the CFL/force bound selects each substep;
- spatial-grid neighbour search, with neighbour pairs reused for density, pressure/viscosity and XSPH passes.

The default bucket/debug pool is `1.2×1.2×1.2 m`, spacing `dx=0.15 m`, fill height `0.6 m` and **320 fluid particles**. A `dam` pool starts as a shorter-width, deeper column. Native limits: dimensions `0.1–2.0 m`, spacing `0.02–0.15 m`, at most `1,024` fluid particles; the game registry allows 8 active pools and caps each tick at 60 substeps. When capped, a pool advances less than the requested 0.05 s and reports its lag rather than running unbounded work.

### Blocks, rigid bodies and buoyancy

While a player is within 64 blocks, the server samples nearby Minecraft `VoxelShape` collision boxes (converted to pool-local AABBs) and nearby dynamic rigid-body AABBs. Inputs are radius-filtered and capped at 512 static boxes and 64 rigid bodies per pool step.

- SPH particles are projected out of static block AABBs and rigid-body AABBs. Contacts remove inward normal velocity with an inelastic response; static shapes are immovable.
- Dynamic rigid contacts return equal-and-opposite impulses to the rigid solver, applied asynchronously on its next step.
- A separate hydrostatic Archimedes impulse uses the pool's conserved particle volume and the body-AABB overlap below the computed water level.
- Static and rigid collisions reposition particles locally; the pool does not create particles, expand its fixed box, or couple to the 2.5D reservoir. Buoyancy is an AABB approximation, not a fully coupled free-surface solver.

These interfaces have native headless tests, but block sampling, live Minecraft ticking and the resulting motion have **not** been verified in a launched game.

### Commands and bucket

```text
/foxphys water sph still   create a still pool above the player
/foxphys water sph dam     create a dam-break pool above the player
/foxphys water sph clear   remove all pools
```

The **Physics Water Bucket** (`foxphys:physics_water_bucket`) is a debug item: right-click a block to place at the clicked face; Shift+right-click creates a dam; right-click air creates a pool near the player. It does not create vanilla water blocks.

A pool advances only while a player in the same dimension is within 64 blocks; server frames are sent to players within 96 blocks. The client currently draws each particle as a small shaded cube and interpolates positions. This is a particle visualization, not a reconstructed liquid surface; client rendering and bucket interactions still need a live test.

## Numerical validation

Headless tests cover the pair-list calculation against a brute-force neighbour sum, finite dam-break behaviour, short-run mass conservation, particle/AABB separation, rigid-body contact impulses, and Archimedes buoyancy. The release performance check is an ignored test:

```bash
cd native
cargo test --locked --release --lib bench_tick -- --ignored --nocapture
```

The benchmark warms each pool for 10 ticks, then measures 100 requested 0.05 s steps on the native CPU harness, **without external block colliders or rigid bodies**. A recorded run after the final neighbour-search/CFL changes reported:

| Spacing | Particles | Average per requested 0.05 s | Substeps/tick |
|---:|---:|---:|---:|
| 0.15 m | 320 | **11.5 ms** | 11 |
| 0.12 m | 600 | **28.5 ms** | 14 |

These results meet the isolated `<15 ms` / `<30 ms` budgets on that run. They are hardware-dependent, the 600-particle result has limited headroom, and they exclude Minecraft chunk/entity work, collider sampling, packet encoding, rendering and other mods. Do not infer an end-to-end TPS guarantee. The reported CFL factor is 0.31; the model is weakly compressible, not physical-acoustics-quality water.

To run the JNI fluid smoke test after building either loader:

```bash
sh validation/fluid/run-fluid-smoke.sh
```

It exercises native operations without a Minecraft client; it is not a live collision or rendering test.

## Limitations

- Both water systems are extremely unstable experimental features. Test only in a throwaway world.
- SPH supports AABB projections and approximate rigid-body impulses, not exact triangle/voxel meshes or fully implicit two-way CFD. Its buoyancy calculation uses the rigid AABB and a hydrostatic water level.
- Pools have a fixed box and fixed particle count. They do not merge/grow; the top is open. There is no foam, refraction, bubbles, splash generation, surface tension or reconstructed fluid surface.
- The artificial sound speed is far below real water's; WCSPH is a practical weakly-compressible approximation, not a full CFD solution.
- The shallow-water reservoir and SPH pools are separate systems. The reservoir is 2.5D; SPH is 3D but visually coarse.
- Actual graphics behaviour, gameplay feel and real-server load have not been checked in Minecraft.
