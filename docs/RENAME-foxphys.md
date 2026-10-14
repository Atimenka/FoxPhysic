# Переименование VoxPhys → FoxPhys (alpha.6, сделано в исходниках)

**FoxPhys — физика лисы в Minecraft.** Полное название: FoxPhysic. Среднее: FoxPhys. Короткое: Fxs.

## Что переименовано

| Что | Было | Стало |
|---|---|---|
| ID мода (`fabric.mod.json`) | `voxphys` | `foxphys` |
| Java-пакет | `dev.voxphys.*` | `dev.foxphys.*` |
| Главный класс, конфиг, блоки, команды | `VoxPhysMod`, `VoxPhysConfig`, `VoxPhysBlocks`, `Commands` | `FoxPhysMod`, `FoxPhysConfig`, `FoxPhysBlocks` |
| JNI-класс | `dev.voxphys.nativecore.NativeBridge` | `dev.foxphys.nativecore.NativeBridge` |
| Rust-крейт | `voxphys-core` / `voxphys_core` | `foxphys-core` / `foxphys_core` |
| Нативная библиотека | `voxphys_core.dll`, `libvoxphys_core.so` | `foxphys_core.dll`, `libfoxphys_core.so` |
| Миксины | `voxphys.mixins.json` | `foxphys.mixins.json` (пакет `dev.foxphys.mixin`) |
| Ресурсы | `assets/voxphys/`, `data/voxphys/` | `assets/foxphys/`, `data/foxphys/` |
| Конфиг | `config/voxphys.json` | `config/foxphys.json` |
| Сохранения | `<мир>/voxphys/<измерение>.json` | `<мир>/foxphys/<измерение>.json` |
| Команды | `/voxphys ...` | `/foxphys ...`, алиас `/fox`, временный `/voxphys` |
| Имя мода | «FoxPhys — Rust Physics Lab» | «FoxPhysic» |
| Артефакты CI | `voxphys-*.jar` | `foxphys-*.jar` |

Логика физики, тесты (кроме путей), лицензия MIT и структура каталогов не менялись.
Исторические логи в `validation/` НЕ переименованы: это записи прошлых прогонов.

## Команды

- `/foxphys ...` — основная команда.
- `/fox ...` — короткий алиас.
- `/voxphys ...` — временный алиас миграции. Выводит предупреждение «команда /voxphys устарела и будет удалена». Удалить в следующем релизе после подтверждения миграции.

## Миграция при запуске

Реализовано в `src/main/java/dev/foxphys/Migration.java`.

- Конфиг: при старте мода, если `config/foxphys.json` отсутствует, а `config/voxphys.json` есть, файл **копируется**.
- Сохранения: при старте сервера каждый файл из `<мир>/voxphys/` (включая подкаталоги) **копируется** в `<мир>/foxphys/`, если там нет файла с тем же путём. Существующие файлы **не перезаписываются**.
- Старые файлы **не удаляются**.
- Если что-то было скопировано, в лог выводится: «VoxPhys переименован в FoxPhys. Конфиги и сохранения мигрированы. Старые файлы сохранены.»

Проверка: `src/test/java/dev/foxphys/MigrationTest.java`, 6 тестов на временных каталогах
(копирование, отсутствие перезаписи, повторный запуск ничего не копирует, рекурсивное копирование). Результат: 6 passed, 0 failures.

**Логотип** `docs/branding/foxphys-logo.png` принят.

## Обновление: стенд удалён, мёд-клей, алиасы проверены

**Стенд (Lab 16×20×16) удалён.** Команды `/foxphys lab` и `/foxphys demo` убраны. Физика создаётся
по месту: сборщик, гравипушка, мёд-клей или `/foxphys spawn` вызывают `LabManager.ensure`. Якорь
координат — точка первого тела. Terrain-сканирование идёт по коробкам вокруг каждого тела.

**Метод сборки как у Sable:** flood-fill (6-связность) от точки. Сборщик режима ASSEMBLE берёт связанные
блоки внутри куба 32³ (до 512 блоков). Мёд-клей берёт связанную группу от кликнутого блока и **отказывает**,
если группа больше 512 блоков или выходит за 32 блока. Частичная сборка не делается, поэтому клей не
отрезает кусок постройки и не забирает землю.

**Мёд-клей:** предмет `foxphys:honey_glue`, рецепт «мёд + сахар → 2 клея» (`data/foxphys/recipe/honey_glue.json`).
ПКМ по блоку склеивает связанную группу в одно тело, расходует 1 клей. Выдача для теста: `/foxphys glue give`.

**Алиасы старых ID:** `voxphys:physics_assembler` и `voxphys:gravity_gun` зарегистрированы с теми же классами
(скрыты из креатива). Модели и loot table лежат в `assets/voxphys` и `data/voxphys`.

### Проверено на запущенном сервере (Fabric 1.21.1, `runServer`, 2026-10-11)
- Мод грузится, миграция копирует `config/voxphys.json` → `config/foxphys.json`, `<мир>/voxphys/` → `<мир>/foxphys/` (подкаталоги тоже). Сообщение миграции выводится один раз.
- `/foxphys`, `/fox`, `/voxphys` зарегистрированы; `/voxphys` показывает предупреждение; `lab` и `demo` в `help` отсутствуют, `soft` есть.
- Сборка без стенда: 8 камней → тело #1 (`foxphys assemble 2`), `clear` вернул блоки на места.
- Soft через команды: spawn, status, pin, stiffness, damping, remove — работают с консоли.
- Старый блок `voxphys:physics_assembler`, поставленный до перезапуска, остаётся в мире после перезапуска сервера.
- Рецепт мёд-клея загружен без ошибок.

### Не проверено в игре
- **Мёд-клей не нажимался:** на тестовом сервере нет игрока (нужен клиент). Проверка ПКМ, расход клея, отказы для больших групп — только вручную.
- Предметы `voxphys:*` в инвентаре игрока (`give` нужен игрок).
- Физика в живом клиенте, визуал, сборка на реальной постройке.

### Ограничения (честно)
1. **Якорь и дальность.** Координаты физики привязаны к точке первого тела. Terrain сканируется только в пределах 64 блоков от якоря (`SCAN_REACH`). Тела дальше не получают коллизий и упадут сквозь мир. Нужна перепривязка якоря или локальные якоря на тело.
2. **NeoForge не начат.** Maven NeoForge в песочнице отвечает `502` (`maven.neoforged.net`). Сборку и порт не делал. Fabric-специфичные места: `FabricLoader`, `ServerLifecycleEvents`, `ServerTickEvents`, `ServerEntityEvents`, `CommandRegistrationCallback`, `ItemGroupEvents`, `UseItemCallback`, mixins с Yarn-именами.
3. **Блоки в старых мирах** `voxphys:*` сохраняют ID (алиасы), но если появится новый блок, миграции нет.
4. **Git-ветки нет** (репозитория нет). Резервная копия до переименования: `/home/user/.cache/backup/voxphys-alpha5.tar`.
5. **Текстура мёд-клея** — `minecraft:item/honey_bottle` (временная).
6. **Версия** осталась `0.1.0-alpha.5`, релиз `alpha.6-foxphys` не выпущен.
7. Modrinth и GitHub не обновлены (доступа нет).
