# FoxPhys alpha.7 in-game closure checklist

> **Do not use a valuable world.** Run each loader in a separate disposable test world, preferably with a backup. Water and soft bodies are extremely unstable experiments. This list is a live-game acceptance record: headless tests do not count as PASS for these items. Mark every unchecked item **NOT TESTED** until observed in a launched Minecraft client.

## Environment and artifact

- Date/time and tester:
- OS / CPU / RAM:
- Java (`java -version`):
- Minecraft version:
- Loader and exact version: Fabric / NeoForge:
- Fabric API version (Fabric only):
- FoxPhys jar filename, size and SHA256:
- NeoForge: Sable Companion JarJar metadata/nested jar visible and no duplicate-class/dependency error:
- GPU / graphics driver / renderer:
- Sodium, Iris, shaders, other mods:
- Single-player or dedicated server:
- Client/server logs attached:

## Startup, native library and API compatibility

Mark PASS / FAIL / NOT TESTED and attach the exact log/chat response.

- Client launches without mixin, payload, JarJar, or native-library error:
- Server launches (if dedicated) with the matching loader/jar:
- `/foxphys status`:
- `/foxphys api`:
- Third-party API integration/compatibility if available:
- `/foxphys spawn wood` and `/foxphys spawn stone`:
- `/foxphys pause true`, then `/foxphys pause false`:
- `/foxphys clear`:
- No regression to existing smoke/test functionality:
- Baseline and test MSPT/TPS:

## Rigid body rendering and player contact

- Spawn and watch a moving/rotating rigid assembly from multiple angles:
- Visible while moving, after chunk boundary crossing and after rejoining:
- Collision response against floor, wall and another rigid body:
- Player stands on a moving physics body without sinking/flicker:
- Player can walk and crouch on the body; camera and view remain stable:
- Player is carried correctly when the body translates/rotates:
- Player can step off/jump away and is not trapped or launched unexpectedly:
- Client log/render errors or screenshots:

## SPH water, blocks, rigid bodies and buoyancy

Give the debug bucket if needed: `/give @s foxphys:physics_water_bucket`.

- `/foxphys water sph still` creates the expected particle pool:
- `/foxphys water sph dam` creates a finite dam-break pool:
- Bucket right-click block: pool appears at clicked face:
- Bucket Shift+right-click block: dam-break mode:
- Bucket right-click air: pool appears near the player:
- Pool particles remain outside a solid block collision shape:
- Pool collides with and displaces particles around a rigid body:
- A floating rigid body shows plausible buoyancy; equal-and-opposite water contact response does not destabilize it:
- `/foxphys water sph clear` removes all pools:
- Pool appearance, clipping, transparency, frame rate:
- MSPT/TPS with one pool and with multiple pools:
- Client/server log excerpts and screenshots:

## Soft-body contacts, self-collision, attachment and tearing

1. In the disposable world, spawn `rubber`, `jelly`, `cloth` and `rope` if practical. Use `/foxphys soft status <id>` to record initial conditions.
2. Move each into a block and a rigid body; fold/overlap cloth and rope; attach a layer to a moving rigid body. Apply enough stress to test tearing.

- Each body is visible with plausible triangles/positions from multiple sides:
- Soft-body particles remain outside block collision shapes:
- Soft body and rigid body push/respond to one another (not only one-way visual follow):
- Self-collision prevents obvious interpenetration of non-neighbouring surfaces:
- Cloth/rope tear under strain; topology updates without client crash or broken mesh:
- Rubber/jelly remain intact under similar stress:
- `/foxphys soft attach <softId> top <bodyId>`; attached layer follows rigid translation/rotation:
- Attachment releases with `/foxphys soft detach <softId>` and restores motion:
- `/foxphys soft remove <id>` cleans up:
- Long-run behaviour and MSPT/TPS:
- Client line `FoxPhys soft body #...` (paste); server errors/recovery (paste):
- Screenshots/video:

## Gravity gun and assembler

- `/foxphys gun give`; right-click grabs/releases:
- Left-click launches in the expected aim direction, without pathological force:
- **R** while holding aligns the body to the grid:
- `/foxphys assembler give`; assembled cells move together:
- `/foxphys disassemble <id>` restores the cells without duplicates/missing blocks:
- Notes/logs:

## Weather/wind and explosion impulse

- Clear weather wind on a soft body and light rigid body:
- Rain wind:
- Thunderstorm wind:
- `/foxphys wind 5 0 0` manual wind response; note if automatic weather later replaces it:
- Explosion source/power/distance and body mass:
- Body moves/rotates away from the blast; no body/world corruption:
- Wall shielding result (approximate radial model; shielding is not modelled):
- MSPT/TPS and errors:

## Result and evidence

- Fabric result: PASS / FAIL / PARTIAL / NOT TESTED:
- NeoForge result: PASS / FAIL / PARTIAL / NOT TESTED:
- Most important defect and exact reproduction:
- Attach both client and server `latest.log`; include crash reports if any:
- Screenshot/video filenames:
- World save included only if disposable and contains no private/valuable data:

**Closure rule:** any in-game criterion above that lacks an observed result remains NOT TESTED. Build success and headless smoke output do not substitute for a live check.
