# Серверная проверка FoxPhys

Нужно: JDK 21, Node 20, локальный сервер Minecraft 1.21.1 с Fabric Loader 0.16.14 и Fabric API 0.116.6+1.21.1, JAR мода с Linux-библиотекой (`-Dfoxphys.nativePath=.../libfoxphys_core.so`). Серверный jar и Fabric launcher берутся с официальных источников Mojang и Fabric; EULA принимается владельцем сервера вручную (`eula=true` в `eula.txt`).

Настройки сервера (`server.properties`): `online-mode=false`, `enable-rcon=true`, `rcon.port=25575`, `rcon.password=...` (в `rcon.py` пароль задаётся параметром).

1. Запуск: `java -Xms512m -Xmx700m -Dfoxphys.nativePath=... -jar fabric-server-launch.jar nogui`
2. Команды через RCON: `python3 rcon.py "foxphys status" "foxphys assemble 2"`
3. Движение игрока: `npm i mineflayer@4` и `node bot.js VoxBot 900` (бот держится рядом со стендом; без игрока рядом лаборатория на паузе).

Проверка «тела не проходят сквозь блоки»: ставится пол, над ним собирается тело, `foxphys status` показывает позицию тела. Центр 2×2×2 тела на полу из камня лежит на y = 80.00 (верх пола 79).
