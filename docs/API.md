# FoxPhys API for third-party mods (version 1)

The public API lives in the packages `dev.foxphys.api` and `dev.foxphys.api.event`. It is present in both builds with the same class and method names:

| Build | Source module | Minecraft names | Events |
|---|---|---|---|
| NeoForge | `neoforge/` + `common/` | Mojang (`ServerLevel`, `Vec3`, `AABB`) | `NeoForge.EVENT_BUS` |
| Fabric | `fabric/` + `common/` | Yarn (`ServerWorld`, `Vec3d`, `Box`) | static fields of `FoxPhysEvents` |

Everything else in `dev.foxphys` is internal: classes and methods may change between releases without notice.

Version: `FoxPhysApi.API_VERSION = 1`. This is the API version, not the mod version (current source version: `0.1.1-beta-release`). The API may change during beta, but the number is raised for any incompatible change.

## Stability policy

- Adding methods, classes, and events does not change `API_VERSION`. Check that a method is available with `FoxPhysApi.isCompatible(n)`.
- Removing a method or changing its meaning raises `API_VERSION`. Old methods are marked `@Deprecated` for at least one release before removal.
- Enum values (`BodyKind`) and protocol operation names are never reused for a different meaning.
- Internal classes (`Lab`, `LabManager`, `Protocol`, `SoftBodies`, `NativeBridge`) are reachable but not part of the API. Use `FoxPhysApi`, `PhysicsWorld`, `SoftBodyApi`, and the events.

## Threads and errors

- All calls must be made on the server thread. `FoxPhysApi.physics`, `FoxPhysApi.ensurePhysics`, and all `PhysicsWorld` methods check the thread and throw `IllegalStateException` before anything changes if they are called from another thread. `SoftBodyApi` does not check the thread (it has no world reference): call it on the server.
- Invalid arguments (non-finite numbers, unknown preset, density outside (0, 100000]) throw `IllegalArgumentException`.
- A world that is unavailable or has failed to start throws `IllegalStateException`.
- Looking up a body that does not exist returns `Optional.empty()`, not an exception.
- Body state is read from the last completed step. A change (impulse, pose) becomes visible in a snapshot after the next tick.

## Quick start

NeoForge:

```java
import dev.foxphys.api.*;
import dev.foxphys.api.event.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

// In the mod constructor:
NeoForge.EVENT_BUS.addListener(BodyAssembledEvent.class, e ->
        MyMod.LOGGER.info("Body #{} from {} blocks", e.bodyId(), e.blockCount()));

// Density for a block of your mod (kg/m^3); applies to new builds:
FoxPhysApi.registerDensity("mymod:rock", 3100);

// On the server:
if (FoxPhysApi.isCompatible(1) && FoxPhysApi.isNativeLoaded()) {
    PhysicsWorld world = FoxPhysApi.ensurePhysics(level, BlockPos.containing(player.position()));
    long id = world.spawnBody(BodyKind.STONE, new Vec3(x, y + 4, z));
    world.body(id).ifPresent(b -> world.applyImpulse(id, new Vec3(0, 500, 0), b.centre()));
}
```

Fabric:

```java
import dev.foxphys.api.*;
import dev.foxphys.api.event.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

// In onInitialize():
FoxPhysEvents.BODY_ASSEMBLED.register(e ->
        MyMod.LOGGER.info("Body #{} from {} blocks", e.bodyId(), e.blockCount()));
FoxPhysEvents.ASSEMBLE_REQUESTED.register(e -> {
    if (e.level().getRegistryKey() == MyMod.PROTECTED_WORLD) e.setCanceled(true);
});

FoxPhysApi.registerDensity("mymod:rock", 3100);

// On the server, level is a ServerWorld:
PhysicsWorld physics = FoxPhysApi.ensurePhysics(level, BlockPos.ofFloored(player.getPos()));
long id = physics.spawnBody(BodyKind.STONE, new Vec3d(x, y + 4, z));
```

## FoxPhysApi

| Member | What it does |
|---|---|
| `API_VERSION` | API version (integer). |
| `isCompatible(int minimumVersion)` | `true` if the API version is at least the given one. |
| `isNativeLoaded()` | `true` if the native physics library loaded. Without it there is no physics. |
| `physics(level)` | The world of a dimension, if it already exists and has not failed. Otherwise empty. |
| `ensurePhysics(level, anchor)` | The world of a dimension; created on the first call with the laboratory origin at `anchor`. Fires `PhysicsWorldCreatedEvent`. |
| `soft()` | Access to soft bodies (`SoftBodyApi`). |
| `registerDensity(key, kgPerM3)` | Density for a block. The key is a path (`"oak_planks"`, for every namespace) or a full id (`"mymod:rock"`; the full id takes priority). Applies to new builds. |
| `registerBlock(block, kgPerM3)` | Registers density for a Minecraft `Block` using its registry id. |
| `registerBlock(block, provider)` | Registers a per-body `IForceProvider` for assemblies containing the block. |
| `registerBlockEntity(type, provider)` | Registers an `IForceProvider` for live block entities of the type. |
| `unregisterBlock(block)`, `unregisterBlockEntity(type)` | Removes a provider registration; `unregisterBlock` also removes a full-id density override. |
| `registeredBlockProviderCount()`, `registeredBlockEntityProviderCount()` | Counts the registered provider types. |
| `unregisterDensity(key)` | Removes a registration. `false` if that key was not registered through the API. |
| `registeredDensities()` | A read-only copy of the densities registered through the API. |
| `densityOf(BlockState)` | The density physics will use for that block right now. |
| `registeredDensityCount()` | How many densities are registered through the API. |
| `describe()` | A log-friendly string: version, natives, table, soft bodies. |

Density lookup order (the same in both builds): densities registered through the API (full id, then path) → the per-block table `block-physics.json` (1060 blocks, see `docs/BLOCK_PHYSICS.md`) → the fallback material table `density-table.json` (full id, then path) → guesses from the block name.

### Force providers

`IForceProvider.sample(Context)` runs on the server thread. Return `null` for no effect, or an `IForceProvider.Force(force, worldPoint, torque)`. A force is in newtons and an optional torque is in N·m; a null point applies the force at the body's centre of mass. Providers receive a snapshot `BodyInfo`, source block/state/position, optional live block entity, and a 0.05 s tick duration. The dispatcher bounds work to 64 queued force/torque commands per dimension tick. Do not mutate the world or block entity from a provider; return values for FoxPhys to queue for the next physics step.

- `registerBlock(block, provider)` samples the provider for each source cell of that block in an assembled rigid body.
- `registerBlockEntity(type, provider)` samples registered block entities near each active rigid body (within the body's bounds expanded by one block).
- Use `unregisterBlock` or `unregisterBlockEntity` when the registration is no longer needed.

## PhysicsWorld (rigid bodies)

A view of the world of one dimension. The object stores only the level, so each call reads the current world. After the world is closed or has failed, calls throw `IllegalStateException`.

| Method | What it does |
|---|---|
| `level()`, `origin()` | The dimension and the laboratory corner in world coordinates. |
| `spawnBody(BodyKind, centre)` | A body from a built-in shape (centre of mass in world coordinates), at rest. Returns the id. |
| `applyImpulse(id, impulse, point)` | Impulse (N·s) at a world point. Synchronous. |
| `applyForce(id, force[, point[, durationSeconds]])` | Force in N at the centre or a world point. Default duration is one 50 ms game tick; duration range is 0.001–10 s. |
| `applyTorque(id, torque[, durationSeconds])` | Torque in N·m; same default/range as force. |
| `setAngularVelocity(id, angularVelocity)` | Sets angular velocity in rad/s in world axes. |
| `setPose(id, centre, quat)` | Moves the body and stops it. The quaternion `x, y, z, w` is normalised automatically. |
| `body(id)` | `Optional<BodyInfo>`: centre, rotation, mass, velocities, bounds, number of parts, `assembled`. |
| `bodies()` | All bodies in the snapshot. |
| `bodiesIn(box)` | Bodies whose bounds intersect the region (world coordinates). |
| `nearestBody(point, radius)` | The nearest body within the radius. |
| `assemble(min, size)` | A cube of blocks → one body. `OptionalLong`; empty if cancelled by an event. |
| `assembleCells(cells)` | The same, for an arbitrary set of cells. |
| `disassemble(id)` | Turns an assembly back into blocks (only for bodies made from blocks, and only if not rotated). |
| `getBlockStates(id)` | Returns a copy of the original Minecraft block states for an assembly; empty for non-assemblies. Order is unspecified. |
| `setPaused(boolean)`, `isPaused()` | Pauses the simulation. |
| `setWind(vec)` | Wind in m/s, world axes. In the mod's automatic tick loop this may be overwritten by weather-driven wind on the next tick; persistent manual override is not implemented. |
| `stats()` | `WorldStats`: `simSeconds`, `fixedSteps`, `bodyCount`, `assemblyCount`, `staticColliders`, `lastStepMicros`, `paused`. |

Body kinds (`BodyKind`): `WOOD` (oak, 1 m cube), `STONE` (stone, 1 m cube), `BOAT` (boat), `WING` (wing).

Not every block can be assembled: air, fluids, block entities, and unbreakable blocks are skipped. World limits: a maximum per call and in total (`Assembler.MAX_ASSEMBLE_CELLS`, `MAX_PHYSICAL_CELLS`).

## SoftBodyApi (soft bodies)

Presets: `rubber`, `jelly`, `cloth`, `rope` (`SoftBodyApi.PRESETS`). Limits: `MAX_BODIES = 16`, `MAX_PARTICLES = 5000` per body. XPBD bodies collide with sampled block AABBs, nearby rigid-body AABBs, one another (vertex self-collision), and an optional floor plane. Cloth/rope can tear at a strain threshold; tearing changes the solver constraints and rendered surface topology. Soft bodies and water remain extremely unstable experimental features.

| Method | What it does |
|---|---|
| `spawn(preset, origin, floorY)` | Creates a body; returns the id. |
| `ids()`, `count()` | The list of ids and the number of bodies. |
| `info(id)` | `Optional<SoftBodyInfo>`: centre, maximum speed, energy, volume ratio, stiffness, damping, finiteness. |
| `remove(id)` | Removes a body. |
| `pin(id, "top"\|"bottom"\|"start"\|"end")` | Pins a layer of particles; returns their count. |
| `pinParticle(id, index)` | Pins one particle. |
| `setStiffness(id, k)` | Stiffness 0.05…100 (1.0 is the preset). |
| `setDamping(id, d)` | Damping 0…50 1/s. |
| `particlePositions(id)` | World `x, y, z` of all particles, as a copy of the array. |
| `isBroken()` | Whether the solver has stopped because of an error. |

Soft bodies are also visible to players through the client renderer (see `docs/SOFT.md`). The triangle mesh that is sent to clients is an internal protocol and is not part of the API.

## Client-side snapshots

`dev.foxphys.api.client.ClientPhysicsApi` is for client code only; do not load it on a dedicated server. Call its static methods on the Minecraft client thread from a render/tick callback. `tickDelta` is clamped to `[0,1]`. Each call returns a defensive snapshot of the already-interpolated render state; changing returned arrays cannot mutate FoxPhys state.

| Method | Result |
|---|---|
| `softBodies(tickDelta)` | `List<SoftBodyFrame>`: body id, preset, interpolated world-space positions and triangle indices. |
| `fluidPools(tickDelta)` | `List<FluidPoolFrame>`: pool id, anchor, particle spacing, interpolated particle positions. |
| `physicsBlocks(tickDelta)` | `List<PhysicsBlockFrame>`: visible rigid-block cells, original `BlockState`s, body ids, interpolated centres and rotations. |

The snapshots expose render-facing geometry, not a stable network protocol. Renderer details and packet formats remain internal.

## Events

All events subclass `FoxPhysEvent`. They are published on the server.

| Event | When | Cancellable |
|---|---|---|
| `AssembleRequestedEvent` | Before blocks are moved into a body. Nothing has changed yet. Fields: world, requested cells. | Yes: the assembly is cancelled and the blocks stay. |
| `BodyAssembledEvent` | A body was created in physics and the blocks were removed from the world. Fields: world, id, block count. | No |
| `BodyDisassembledEvent` | A body was taken apart and the blocks returned. Fields: world, id, positions of the returned blocks, number of dropped blocks. | No |
| `BodySpawnedEvent` | A rigid body was added (through the API and `/foxphys spawn`). Fields: world, id, kind. | No |
| `PhysicsWorldCreatedEvent` | A dimension got a physics world for the first time. Fields: world, laboratory corner. | No |
| `PhysicsWorldTickEvent` | Every server tick, for each world, after the step. Field: world. | No |
| `SoftBodySpawnedEvent` | A soft body was created. Fields: id, preset. | No |
| `SoftBodyRemovedEvent` | A soft body was removed. Field: id. | No |

Subscribing:
- NeoForge: `NeoForge.EVENT_BUS.addListener(BodySpawnedEvent.class, e -> ...)`.
- Fabric: `FoxPhysEvents.BODY_SPAWNED.register(e -> ...)`. The list of fields is in `FoxPhysEvents`.

Cancelling (`AssembleRequestedEvent.setCanceled(true)`) works in both builds, but the order in which listeners are called after a cancellation differs:
- Fabric: all listeners are called; the assembly is cancelled if at least one of them cancelled it.
- NeoForge: listeners registered without `receiveCanceled` do not receive the event after it was cancelled. If you need every listener to run, register with `addListener(EventPriority.NORMAL, true, Class, consumer)`.

## Limitations of version 1

- Boat buoyancy uses only the material (air inside is not counted).
- Registering a density does not recalculate existing bodies.
- Block and rigid-body contact is sampled AABB collision; soft↔rigid contact forces are approximate and are not a full deformable/rigid solver.
- The client snapshot API exposes interpolated render data but not controls over the network protocol or custom renderer registration.
- Soft attachments, wind, and SPH pool management are still internal systems; player commands are available. Water and soft bodies are extremely unstable in alpha.7.
## Verification scope

Headless API smoke tests cover compatibility checks, densities, events, soft-body calls, and event cancellation in both loader mappings. They do not start a Minecraft client or verify world interactions. The smoke scripts are `validation/api/run-fabric-smoke.sh` and `validation/api/run-neoforge-smoke.sh`; see [Build and testing](BUILD_AND_TESTING.md) for prerequisites and scope.
