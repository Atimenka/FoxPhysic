# Windows dedicated server setup (Fabric)

This guide and `start-server.bat` are for **Fabric on Minecraft 1.21.1**. NeoForge requires a NeoForge server installation and is not launched by this script.

## Requirements

- Windows 10/11 x64.
- Java 21 (`java -version` should report 21).
- Fabric Loader 0.16.14+ server launcher for Minecraft 1.21.1.
- Fabric API 0.116.6+1.21.1 and the FoxPhys Fabric jar.
- 8 GB RAM recommended for a small local test server. `start-server.bat` requests 2 GB initial and 4 GB maximum heap; change those values only if the host has enough memory available.
- A Minecraft client with the same Minecraft version, Fabric Loader, Fabric API, and FoxPhys jar. The mod is required on both sides for custom network payloads and rendering.

## Prepare a local server

1. Create a separate directory such as `C:\foxphys-server\`.
2. Put `fabric-server-launch.jar` in that directory. Download the Fabric server launcher for Minecraft 1.21.1 from the official Fabric site; rename it to match the batch file or edit the command.
3. Create `mods\` and place `fabric-api-0.116.6+1.21.1.jar` plus `foxphys-fabric-0.1.1-beta-release.jar` inside.
4. Copy `start-server.bat` into the server directory.
5. Start once to generate `eula.txt`. Read the EULA and set `eula=true` only if you accept it.
6. For a private local test, configure `server.properties` accordingly. Never expose a server with `online-mode=false` publicly; use authenticated online mode for a public server.

Start with `start-server.bat`. Wait for the server-ready message, then join from the matching client. Grant operator rights from the console with `op <player_name>`. FoxPhys commands require operator permission level 2.

## Recommended first checks

Use the [in-game checklist](../../docs/IN_GAME_TEST_CHECKLIST.md) and a disposable Creative world. Start with:

```text
/foxphys status
/foxphys spawn wood
/foxphys spawn stone
/foxphys soft spawn jelly
/foxphys soft status
```

For the 2.5D reservoir try `/foxphys water still`; for a debug 3D particle pool try `/foxphys water sph still`. The physics water bucket is a debug item. Keep the player near the physics world; simulation may pause when no player is nearby.

## Logs and measurements

- Server log: `logs\latest.log`.
- Client log: the instance's `logs\latest.log`.
- Crash reports: `crash-reports\`, if generated.
- TPS/MSPT: use `/tick query` if available, or record the server's tick metrics before and during a short test.
- Physics details: `/foxphys status`.

Record the exact mod JAR filename and SHA256 from the beta release checksum file after building it; the `release/alpha.7/SHA256SUMS.txt` applies only to alpha.7. Attach the completed checklist and relevant client/server logs when reporting a defect. Do not share a world save containing private or valuable data.
