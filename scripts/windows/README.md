# Windows-сервер для игрового теста FoxPhys 0.1.0-alpha.5

Требования: Windows 10/11 x64, Java 21 (`java -version` показывает 21), 8 ГБ ОЗУ и больше. Клиент Minecraft нужен только для входа игрока на сервер.

## 1. Папка сервера

Создайте папку, например `C:\foxphys-server\`, и положите туда:

1. `fabric-server-launch.jar` — серверный лаунчер Fabric Loader 0.16.14 для Minecraft 1.21.1 (скачать на https://fabricmc.net/use/server/ и переименовать в `fabric-server-launch.jar`, либо поправить имя в `start-server.bat`).
2. Папку `mods\` с двумя jar:
   - `fabric-api-0.116.6+1.21.1.jar` (Fabric API 0.116.6+1.21.1, Modrinth, проект fabric-api);
   - `foxphys-0.1.0-alpha.5-windows-x64.jar` (из `/home/user/foxphys-release/`, проверьте SHA256 по `SHA256SUMS.txt`).
3. `start-server.bat` из `scripts\windows\` этого репозитория.

Первый запуск создаст `eula.txt`. Откройте его, прочитайте Minecraft EULA и поставьте `eula=true`, только если вы принимаете его.

В `server.properties` для теста достаточно: `online-mode=false` (только для локального теста без аккаунта), `enable-rcon=false`, `view-distance=6`. Для публичного сервера `online-mode` должен быть `true`.

## 2. Запуск и вход

1. Запустите `start-server.bat`. В логе должна быть строка `FoxPhys alpha ready`.
2. Зайдите клиентом Minecraft 1.21.1 с Fabric Loader 0.16.14 и Fabric API 0.116.6+1.21.1. На клиент ставится только Fabric API, мод FoxPhys клиенту не нужен.
3. Дайте себе права оператора: в консоли сервера `op <ваш_ник>`.
4. Проверьте команды по `alpha.5-ingame-report-TEMPLATE.txt`.

## 3. Замеры

- TPS и MSPT: `/tick query`. Снимайте до `demo`, во время теста (2 минуты) и после.
- Время нативного шага и ошибка объёма воды: `/foxphys status`.
- Важно: физика работает только при игроке рядом со стендом (в пределах 64 блоков от центра). Без игрока стенд на паузе, это штатное поведение. Для теста остаётесь рядом.

## 4. Логи для отчёта

- `logs\latest.log` сервера;
- папка `crash-reports\`, если она появилась;
- вывод `/tick query` и `/foxphys status`, скопируйте в шаблон отчёта.

Шаблон отчёта: `validation/alpha.5-ingame-report-TEMPLATE.txt`. Заполненный отчёт сохраните как `validation/alpha.5-ingame-<дата>.txt` и пришлите в чат вместе с логами.

## 5. Проверки alpha.5 в игре (за пользователем)

Агент проверил на Linux-сервере контакт тела с полом, перекрытие игрока с телом (без разгона тела), `assemble` и `disassemble`. Эти пункты нужно повторить живым клиентом на Windows:

1. Стояние сверху: встать на тело на полу (тело под вами, вы сверху). Ожидается: вы стоите на верхней грани, тело не уезжает и не разгоняется. Если вас кикает с «multiplayer.disconnect.flying», запишите время и текст.
2. Удар пустой рукой по телу на полу: `item replace entity @s weapon.mainhand with minecraft:air`, затем ЛКМ по телу. Ожидается сообщение «Удар: тело #…» и откидывание тела.
3. Один взмах гравипушкой: взять пушку, навести на тело, ПКМ (захват), затем один ЛКМ. Ожидается одно сообщение «запуск», без «Удар».
4. Предмет assembler: `/foxphys assembler give`, затем поставить блок и выполнить `/foxphys assembler run <позиция>`. Ожидается сборка тела из блоков.

Результаты пишите в `validation/alpha.5-ingame-<дата>.txt` по шаблону `validation/alpha.4-ingame-report-TEMPLATE.txt`. Логи сервера — из `logs\latest.log`.
