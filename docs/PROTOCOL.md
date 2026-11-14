# Native JNI API and JSON protocol (ABI 1)

Java class: `dev.foxphys.nativecore.NativeBridge`. Four static exports:

```java
int abiVersion();
long create(String configJson);
String exchange(long handle, String requestJson);
void destroy(long handle);
```

`handle` is a monotonically allocated integer in a mutex-protected Rust registry. It is **not a raw pointer**. Closed or unknown handles and malformed requests raise a Java `IllegalStateException`. Destruction releases the entire Rapier/fluid world. Up to 8 simultaneous native worlds; 8 MiB maximum JSON request.

Configuration (other fields get explicit defaults):

```json
{"gravity":9.81,"air_density":1.225,"water_density":998.2,"step_hz":120,"max_bodies":0}
```

An exchange may contain an environment replacement, commands, and logical time:

```json
{
  "dt":0.05,
  "terrain":[{"center":[8,-0.5,8],"size":[16,1,16]}],
  "water_cells":[],
  "commands":[
    {"op":"add_body","body":{
      "id":1,"position":[8,5,8],"rotation":[0,0,0,1],"velocity":[0,0,0],
      "parts":[{"offset":[0,0,0],"size":[1,1,1],"density":550,"block":"minecraft:oak_planks"}]
    }},
    {"op":"set_wind","velocity":[0,0,-20]}
  ]
}
```

- All coordinates are relative to the Java world's fixed integer lab origin.
- `dt` is 0..0.1 seconds; `dt=0` applies commands without advancing the physics.
- Omit `terrain` / `water_cells` to keep the previous data. Empty arrays clear them.
- `terrain` contains axis-aligned collision boxes sampled from the block shapes.
- `water_cells`: `{"cell":[x,y,z],"height":1,"velocity":[0,0,0]}`. These are sampled vanilla water columns, NOT the dynamic reservoir state.
- `parts` offsets refer to the rigid body's model origin. Mass, centre of mass, and inertia come from all attached colliders; parts cannot overlap.
- The ID is a positive 64-bit signed integer. Body count limit: `max_bodies` (0 = no limit, 1..10000 otherwise; the config default is 64). The engine itself has no body cap. An assembled body may contain up to 512 parts/colliders; the native world also caps the aggregate at 512 parts.
- Unknown JSON fields are rejected instead of being silently ignored.
- Environment sections are validated before replacement. Commands are validated individually and applied **in order**, not as an all-or-nothing transaction. Check every `results[i].ok`.

Additional commands:

```json
{"op":"remove_body","id":1}
{"op":"impulse","id":1,"impulse":[3000,0,0]}
{"op":"impulse","id":1,"impulse":[0,3000,0],"point":[8.5,5,8]}
{"op":"pause","paused":true}
{"op":"set_tank","tank":{"origin":[2,4,2],"nx":12,"nz":12,"dx":1,"depth_left":3,"depth_right":0.35,"viscosity":0.001,"damping":0.04,"roughness":0}}
{"op":"remove_tank"}
{"op":"add_spring","spring":{"id":1,"a":null,"b":1,"anchor_a":[8,9,8],"anchor_b":[0,0,0],"rest_length":2,"stiffness":1000,"damping":100}}
{"op":"remove_spring","id":1}
```

The optional impulse `point` is in **lab coordinates**, while a spring's `anchor_b` is body-local and `anchor_a` is local to A, or lab-space if `a=null`.

The snapshot fields match `Protocol.java`: ABI, logical seconds and fixed steps, bodies (pose, velocities, parts, mass, buoyancy and air-force diagnostics), reservoir depths and volume error, wind, native exchange time, number of static colliders, and per-command results. Depths use row-major indexing `z*nx+x`. The timing excludes most Java sampling, rendering, and network time, and must not be presented as the total frame time.

JSON is deliberately a debuggable **prototype** transport. For large moving worlds, replace it with versioned direct buffers or batched commands after a measured profile, preserving input validation and ownership. JNI should exchange snapshots, not make a call per force sample.

## Block assembly, attachment, and terrain snapshots

Optional fields use their documented defaults. Unknown request fields are rejected; response consumers should ignore fields they do not use.

**Block assembler.** A block cell: `{"cell":[x,y,z],"density":800,"friction":0.6,"restitution":0,"block":"minecraft:stone"}`; `cell` is an integer coordinate, and the block occupies `[c, c+1]³`.

```json
{"op":"assemble_body","id":10,"cells":[ ...cells... ]}
{"op":"attach_cells","id":10,"cells":[ ...cells... ]}
{"op":"disassemble_body","id":10}
```

- `assemble_body` creates one body from unique cells. Duplicates (even with different materials) are rejected.
- `attach_cells` requires a body aligned to the grid (a multiple of 90°).
- `disassemble_body` removes the body and returns the cells in the **command result**: `results[i].cells = [[x,y,z], ...]`. The field `cells` is present only when the disassembly succeeds.
- Commands are not atomic: check `results[i].ok`. A refusal does not change the state of the body.

**Reservoir roughness.** The field `roughness` (Manning coefficient `n`, `0..0.2`, default `0`) enables friction on the bottom.

**Body snapshot.** Each body contains `aabb_min` and `aabb_max`: the exact world bounds of all colliders, including rotation (`|R|·half-size`). This is the hitbox for presentation and interaction; the colliders themselves remain exact boxes.

**Errors.** Invalid cells, duplicates, exceeding collider limits, a tilted body, and an invalid density are returned as `ok:false` with a message. Top-level JSON errors still lead to `IllegalStateException`.

## Incremental terrain and grid-aligned disassembly

**Incremental terrain.** `{"op":"patch_terrain","add":[{"key":K,"center":[..],"size":[..]}],"remove":[K,...]}`.
- `add` inserts a box under the key `key` (u64, chosen by Java) or replaces the box with the same key. `remove` deletes keys; an unknown key is ignored, so a patch can be safely repeated.
- The command is validated as a whole before any change. A duplicate key in `add`, an invalid box, or exceeding 16384 boxes rejects the command; the terrain stays as it was.
- Java normally sends only the difference using its own key map. A full replacement through the `terrain` field is also accepted (keys 0..n).

**Disassembly with grid snapping.** `{"op":"disassemble_body","id":N,"snap":true}`. A body that is off the grid because of an integer or fractional shift is rounded to whole blocks. The rotation must still be a multiple of 90°. The command refuses (the body is not removed) if the body's parts have different fractional shifts (a non-rigid shift) or a shift of exactly half a block. If `snap` is omitted, the body must already satisfy the grid-alignment checks.

**Assembly records in saves.** In `<world>/foxphys/<dimension>.json`, next to `state`, `assemblies` stores `[{"id":N,"cells":[{"pos":[x,y,z],"block":"minecraft:..."}]}]`. Positions are integer cells in the lab coordinate frame. A block state is saved by its identifier; state properties such as stair orientation are not preserved by this record.

**Disassembly result.** `results[i].cells` contains the cells that Rust occupied after rounding. Java matches them against the recorded shape by a shift of the minimum corner and checks that all cells match.

## Joints, persistence, and solver settings

Optional fields use the defaults listed below. Check each command result before assuming the state changed.

**Joints.** `add_joint` / `remove_joint`. The type is `kind`:

| kind | Free degree of freedom | `limits` | Note |
| --- | --- | --- | --- |
| `hinge` | rotation about `axis` | angles in radians, `[lo, hi]` | hinge |
| `slider` | translation along `axis` | offset in metres, `[lo, hi]` | sliding connection |
| `cone_twist` | rotation within a cone | `[0, θ]`, uses `θ = limits[1]` | limits on AngY and AngZ, approximate |
| `rope` | distance ≤ `max_length` | not used | rope; a chain is a set of ropes |

- `a: null` — the joint is attached to the world (a fixed point `anchor_a` in world coordinates).
- `axis` is given in the local frame of both bodies and must physically match at creation.
- `break_force` (N, `1..10 000 000`): the joint breaks if the force exceeds the value. The break appears in the snapshot as `broken_joints: [id, ...]` once, in the same response in which it happened.
- The snapshot contains `joint_count`.
- Bodies referenced by a joint or spring remove those links when the body is removed.

**Saving.** `export_state` returns `results[i].state` (format version `format: 1`): bodies with position, rotation, linear and angular velocities, springs, joints, and wind. The environment (terrain, water, reservoir) is **not** saved: Java passes it on every scan.
`import_state` replaces the bodies, springs, and joints. Validation is done before any change. If the application fails, the world is rolled back to the state before the call. An unknown `format` is rejected.

**Solver.** In `config`: `solver_iterations` (1..8, default 4) and `ccd_substeps` (1..8, default 4). Increasing them can improve contact robustness at additional CPU cost; profile a representative scene before changing them.

**Bodies.** The angular velocity of a body is stored in `SavedState` separately from `Body` (`AddBody` does not have it and does not need it).

## Asynchronous physics worker

Each native physics world runs on a worker thread named `foxphys-physics`. The world steps in real time and does not depend on server ticks.

| Function | Behaviour |
| --- | --- |
| `workerCreate(config)` | creates the thread, returns a handle |
| `workerPush(h, request)` | queues the input and does not wait; the input is applied between steps and does not advance time |
| `workerCall(h, request)` | the same, but waits for the result up to 2 s; for commands, export, and import |
| `workerLatest(h)` | the last published snapshot (JSON) or `null` before the first step; does not block |
| `workerTakeError(h)` | `""`, `"F:..."` (fatal: a step failed, the stand is stopped) or `"R:..."` (input rejected, the world keeps running); each error is reported once |
| `workerSetIdle(h, idle)` | idle: 10 Hz, steps up to 0.1 s. Java pauses the stand when no players are nearby, so bodies do not move while idle |
| `workerDestroy(h)` | stops the thread and waits for it to finish |

Catch-up: in active mode, at most 4 fixed steps per cycle, so under overload the simulation slows down instead of accumulating debt.

## Soft bodies, attachment, wind, and SPH pools

Two JSON entry points complement the rigid-body exchange: `NativeBridge.softExchange(String) -> String` (soft bodies, `soft_world.rs`) and `NativeBridge.fluidExchange(String) -> String` (SPH pools, `fluid_world.rs`). Each request is an object with an `op` field. A parse error, an unknown operation (`unknown soft op 'x'`), or a world refusal does not return JSON: JNI throws a Java exception (`fail`), and the calling code (`SoftBodies`, `FluidWater`) catches it and writes to the log.

### Soft bodies (`softExchange`)

| `op` | Request fields | Response |
|---|---|---|
| `spawn` | `preset` (rubber, jelly, cloth, rope), `origin` [x,y,z], `floor` (floor height) | `id`, `particles` |
| `step` | `dt`, `substeps` (default 8), optional global `obstacles`/`bodies`, or per-body `environments` entries `{id, obstacles, bodies}` | elapsed `ms`, bounds, torn-constraint count/tear events, and accumulated `body_impulses` |
| `status` | `id` | centre, `vmax`, `volume_ratio_min/max`, `stiffness`, `damping`, `preset` |
| `positions` | `id`, `tris` (default `true`; `false` — without the surface) | `pos` (x,y,z per particle, metres, rounded to 1 mm; in world coordinates, like `origin` in `spawn`), `tris` (indices of the cached boundary triangles; refreshed after tearing), `preset` |
| `list` | — | `bodies` (ids) |
| `pin` | `id`, `side` (top, bottom, start, end) | `pinned` |
| `anchor` | `id`, `side` | `indices` of the anchored particles (soft fixing that remembers the mass) |
| `anchor_targets` | `id`, `targets`: array of `[index, x, y, z]` | — (refused if a particle is not anchored) |
| `release` | `id` | `released` (number of particles; the mass is restored) |
| `wind` | `velocity` [x,y,z] | `wind` (components ±60 m/s) |
| `stiffness`, `damping` | `id`, `value` | — |
| `remove` | `id` | — |

Wind is applied to all bodies as an aerodynamic drag per particle. Static boxes use `{min:[x,y,z], max:[x,y,z]}`; dynamic bodies additionally carry `{id, velocity:[x,y,z], mass}`. A step projects particle contacts, runs self-collision, and returns reaction impulses for rigid contacts. Per-body `environments` override the global `obstacles`/`bodies` lists for that soft-body ID. Cloth/rope tearing rebuilds the cached surface when constraints break. Attachment holds particles at targets that Java computes from the pose of the rigid body (`SoftAttachments`); this kinematic anchor itself is one-way.

### SPH pools (`fluidExchange`)

| `op` | Request fields | Response |
|---|---|---|
| `create` | `size` [x,y,z], `fill_height`, `dx`, `shape` (`still`, `dam`) | `id`, `particles` |
| `step` | `id`, `seconds`, `max_steps` (up to 400), optional `obstacles` AABBs and `bodies` (`{id,min,max,velocity,mass}`) | `steps`, `advanced`, `requested`, `sim_time`, accumulated `body_impulses` (including contact and hydrostatic buoyancy impulses) |
| `positions` | `id` | `n` (number of particles), `xyz` (coordinates relative to the anchor, flat list) |
| `status` | `id` | `particles`, `size`, `dx`, `fill_height`, `shape`, `mass`, `mean_density`, `kinetic_energy`, `top`, `sim_time` |
| `remove` | `id` | — |
| `list` | — | `pools` (ids) |

The time step is chosen by the solver (`stable_dt`, the CFL and force conditions). The `seconds` field is physical time; `max_steps` limits the number of substeps per call. Each request accepts at most 512 static boxes and 64 dynamic bodies; distant AABBs are discarded relative to the pool. Static/dynamic contacts project SPH particles; dynamic contacts return reaction impulses, and an additional AABB-based Archimedes estimate supplies buoyancy. The in-game integration caps at 60 substeps/tick and 8 active pools; the native registry itself supports 16 pools.

### Network packets (Java, channel `foxphys:...`)

| Packet | Direction | Contents |
|---|---|---|
| `soft_meshes` (`SoftMeshes`) | server → client, every tick | for each soft body: id, preset, positions and triangles. The triangles are sent in full when a body appears and every 20 ticks; on other ticks the list is empty, and the client takes the surface from its cache by id |
| `fluid_frame` (`FluidFrame`) | server → client, every tick | for each pool near a player: id, anchor, `dx`, positions |
| `align_request` (`AlignRequest`) | client → server | the R key pressed with the gravity gun in hand (no data) |

Packets are encoded the same way in both builds (`FriendlyByteBuf` and `RegistryByteBuf`/`PacketCodec`). Registration: NeoForge `RegisterPayloadHandlersEvent`, Fabric `PayloadTypeRegistry`.
