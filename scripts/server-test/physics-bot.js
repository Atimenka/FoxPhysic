// Alpha.5 interaction check: a real client connection that looks at bodies, swings and right-clicks.
// Usage: NODE_PATH=<dir with mineflayer> node physics-bot.js <scenario> [name]
// Scenarios: gun (grab, then launch by swing), hit (hand hits, empty hand), stand (idle, logs height).
const mineflayer = require('mineflayer');
const scenario = process.argv[2] || 'gun';
const name = process.argv[3] || 'VoxBot';
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, version: '1.21.1', auth: 'offline', physicsEnabled: false });
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pos = () => { const p = bot.entity.position; return `(${p.x.toFixed(2)}, ${p.y.toFixed(2)}, ${p.z.toFixed(2)})`; };

bot.on('message', (m, position) => log(position === 'game_info' ? 'ACTIONBAR' : 'CHAT', m.toString()));
bot.on('kicked', (r) => log('kicked', JSON.stringify(r)));
bot.on('error', (e) => log('error', e.message));
bot.once('spawn', async () => {
  log('spawned', pos());
  // Keep the first seconds still so the server can finish the lab and the chunks.
  await sleep(Number(process.env.BOT_WAIT_MS || 15000));
  if (scenario === 'gun') {
    bot.look(0, -Math.PI / 2, true); // straight up, at the body above the head
    await sleep(1000);
    log('right-click: grab');
    bot.activateItem();
    await sleep(2500);
    log('left-click: launch');
    bot.swingArm('right');
    await sleep(1500);
    log('after launch', pos());
  } else if (scenario === 'hit') {
    bot.look(0, -Math.PI / 2, true);
    await sleep(800);
    for (let i = 0; i < 3; i++) { log('hit', i + 1); bot.swingArm('right'); await sleep(1000); }
  } else if (scenario === 'hold') {
    // Stay connected (no actions) so the operator can drive the server with RCON.
    for (let i = 0; i < Number(process.env.BOT_HOLD_S || 120); i++) { await sleep(1000); }
  } else if (scenario === 'control') {
    // Command file channel: one command per line, read every 200 ms, then the file is cleared.
    // look x y z | use | swing | quit   (look aims the head at a world point)
    const file = process.env.BOT_CMD || '/home/user/.cache/bot-cmd.txt';
    const fs = require('fs');
    const end = Date.now() + Number(process.env.BOT_HOLD_S || 300) * 1000;
    while (Date.now() < end) {
      await sleep(200);
      let text = '';
      try { text = fs.readFileSync(file, 'utf8'); } catch (e) { continue; }
      if (!text.trim()) continue;
      fs.writeFileSync(file, '');
      for (const line of text.split('\n').map((l) => l.trim()).filter(Boolean)) {
        const [cmd, ...args] = line.split(/\s+/);
        if (cmd === 'look') {
          const { Vec3 } = require('vec3');
          bot.lookAt(new Vec3(Number(args[0]), Number(args[1]), Number(args[2])), true);
          log('look', args.join(' '));
        } else if (cmd === 'rawlook') {
          // Sends only the look packet (degrees): the server takes the aim, nothing else moves.
          // Keep the client's own angles in sync, or its periodic packets would reset the aim.
          const yawRad = Number(args[0]) * Math.PI / 180, pitchRad = Number(args[1]) * Math.PI / 180;
          bot.entity.yaw = yawRad; bot.entity.pitch = pitchRad;
          bot._client.write('look', { yaw: Number(args[0]), pitch: Number(args[1]), onGround: true, flags: { onGround: true, hasHorizontalCollision: undefined } });
          log('rawlook', args.join(' '));
        }
        else if (cmd === 'use') { log('use'); bot.activateItem(); }
        else if (cmd === 'useraw') {
          // use_item carries the client's rotation (degrees, x = yaw, y = pitch); the server applies it.
          // mineflayer sends 0/0 there, which would reset the aim, so the packet is written here.
          bot._client.write('use_item', { hand: 0, sequence: Math.floor(Math.random() * 1e6), rotation: { x: Number(args[0]), y: Number(args[1]) } });
          log('useraw', args.join(' '));
        }
        else if (cmd === 'swing') { log('swing'); bot.swingArm('right'); }
        else if (cmd === 'quit') { log('quit'); bot.quit(); process.exit(0); }
      }
    }
    } else if (scenario === 'stand') {
    for (let i = 0; i < 20; i++) { log('stand y', bot.entity.position.y.toFixed(3), pos()); await sleep(500); }
  }
  log('done');
  bot.quit();
  process.exit(0);
});
