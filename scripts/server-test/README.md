# Optional dedicated-server test harness

The scripts in this folder automate a **local Fabric server** test with RCON and a Mineflayer player. They do not download or install Minecraft for you, do not accept the EULA, and are not a replacement for the manual [in-game checklist](../../docs/IN_GAME_TEST_CHECKLIST.md).

## Requirements

- JDK 21, Node.js 20, Python 3.
- A private Minecraft 1.21.1 Fabric server with Fabric Loader 0.16.14+, Fabric API 0.116.6+1.21.1, and the FoxPhys Fabric jar.
- A matching client mod set if a human is joining.
- A separate disposable world.

## Start the server

Download and install the official Fabric server launcher yourself. Accept the Minecraft EULA only after reading it. For a local isolated harness, configure RCON in `server.properties`, for example:

```properties
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=choose-a-local-test-password
```

Do not expose this unauthenticated/RCON-enabled test server to the public Internet. Keep passwords out of shared logs.

Start the server with a Java 21 command such as:

```bash
java -Xms512m -Xmx2g -jar fabric-server-launch.jar nogui
```

RCON commands can be sent with `rcon.py` (the script accepts the password as an argument; avoid shell history or shared logs when using a real credential):

```bash
python3 rcon.py "foxphys status" "foxphys spawn wood"
```

Install Mineflayer in this folder if you intend to use the bot, then start it with the configured player name and duration supported by `bot.js`. The bot can keep a player near an active physics body so the idle pause does not stop the simulation. Review `bot.js` and `physics-bot.js` before connecting; they are test clients, not production automation.

## Suggested checks

- Confirm the server starts without native-library, mixin, or payload errors.
- Run `/foxphys status`, spawn a body, and observe its position and contact with a floor.
- Keep a player near the active body while checking that simulation progresses; move away and confirm idle pausing if desired.
- Use the manual checklist for visual work, player collision, attachments, wind, the gravity gun, or the water bucket.

Capture both server and client `latest.log` files and any crash report. This harness does not validate renderer output, Windows native execution, or gameplay on NeoForge.
