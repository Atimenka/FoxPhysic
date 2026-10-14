# FoxPhys

**Physics engine for Minecraft.** Real water, soft bodies, aerodynamics, and moving structures.

FoxPhys is a **Fabric/NeoForge** mod that adds a **Rust-powered physics engine** to Minecraft. It's built to work **alongside Sable**, not replace it.

## What it does

- **Real 3D water** — SPH-based fluid simulation with refraction, waves, and foam.
- **Soft bodies** — cloth, ropes, jelly, and rubber that deform and react.
- **Aerodynamics** — wings generate lift, stall, and respond to wind.
- **Moving structures** — assemble blocks into physical bodies that collide, float, and break apart.
- **Honey Glue** — connect blocks into a structure, just like Create: Aeronautics.

## Requirements

- **Fabric** or **NeoForge** (Minecraft 1.21.1)
- **Fabric API** (if using Fabric)
- **Sable** (optional)
- **Sable Companion** (included)

## Status

**Alpha.6** — experimental. Physics starts on first use: assemble blocks, use honey glue, or `/foxphys spawn`.

**Known issues:**
- BetterGrassify is incompatible with Sodium 0.8.13 (not FoxPhys).
- Client renderer for moving blocks is not finished.
- 3D water is in development.

## License

MIT
