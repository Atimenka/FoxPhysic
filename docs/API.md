# FoxPhys API для сторонних модов (версия 1)

Публичный API лежит в пакетах `dev.foxphys.api` и `dev.foxphys.api.event`. Он есть в двух сборках с одинаковыми именами классов и методов:

| Сборка | Minecraft-имена | События |
|---|---|---|
| NeoForge (`neoforge/`) | Mojang (`ServerLevel`, `Vec3`, `AABB`) | `NeoForge.EVENT_BUS` |
| Fabric (`src/`) | Yarn (`ServerWorld`, `Vec3d`, `Box`) | статические поля `FoxPhysEvents` |

Всё остальное в `dev.foxphys` — внутреннее: классы и методы могут меняться между релизами без предупреждения.

Версия: `FoxPhysApi.API_VERSION = 1`. Это версия API, не мода (мод сейчас `0.1.0-alpha.7`). В alpha API может меняться, но номер поднимается при любом несовместимом изменении.

## Политика стабильности

- Добавление методов, классов и событий не меняет `API_VERSION`. Проверяйте наличие нужного метода через `FoxPhysApi.isCompatible(n)`.
- Удаление или изменение смысла метода повышает `API_VERSION`. Старые методы помечаются `@Deprecated` минимум на один релиз до удаления.
- Значения перечислений (`BodyKind`) и имена операций протокола не переиспользуются для другого смысла.
- Внутренние классы (`Lab`, `LabManager`, `Protocol`, `SoftBodies`, `NativeBridge`) доступны, но API не включает их. Используйте `FoxPhysApi`, `PhysicsWorld`, `SoftBodyApi` и события.

## Потоки и ошибки

- Все вызовы — только в серверном потоке. `FoxPhysApi.physics`, `FoxPhysApi.ensurePhysics` и все методы `PhysicsWorld` проверяют поток и при вызове из другого потока бросают `IllegalStateException` до того, как что-либо изменится. `SoftBodyApi` потока не проверяет (у него нет мира): вызывайте его на сервере.
- Неверные аргументы (нефинитные числа, неизвестный пресет, плотность вне (0, 100000]) — `IllegalArgumentException`.
- Мир недоступен или отказал при запуске — `IllegalStateException`.
- Поиск несуществующего тела возвращает `Optional.empty()`, а не исключение.
- Состояние тел читается из последнего завершённого шага. Изменение (импульс, поза) видно в снимке после следующего тика.

## Быстрый старт

NeoForge:

```java
import dev.foxphys.api.*;
import dev.foxphys.api.event.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

// В конструкторе мода:
NeoForge.EVENT_BUS.addListener(BodyAssembledEvent.class, e ->
        MyMod.LOGGER.info("Тело #{} из {} блоков", e.bodyId(), e.blockCount()));

// Плотность для блока своего мода (кг/м^3), учитывается в новых сборках:
FoxPhysApi.registerDensity("mymod:rock", 3100);

// На сервере:
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

// В onInitialize():
FoxPhysEvents.BODY_ASSEMBLED.register(e ->
        MyMod.LOGGER.info("Тело #{} из {} блоков", e.bodyId(), e.blockCount()));
FoxPhysEvents.ASSEMBLE_REQUESTED.register(e -> {
    if (e.level().getRegistryKey() == MyMod.PROTECTED_WORLD) e.setCanceled(true);
});

FoxPhysApi.registerDensity("mymod:rock", 3100);

// На сервере, level — ServerWorld:
PhysicsWorld physics = FoxPhysApi.ensurePhysics(level, BlockPos.ofFloored(player.getPos()));
long id = physics.spawnBody(BodyKind.STONE, new Vec3d(x, y + 4, z));
```

## FoxPhysApi

| Метод | Что делает |
|---|---|
| `API_VERSION` | Версия API (целое число). |
| `isCompatible(int minimumVersion)` | `true`, если версия API не меньше указанной. |
| `isNativeLoaded()` | `true`, если нативная библиотека физики загрузилась. Без неё физики нет. |
| `physics(level)` | Мир измерения, если он уже есть и не отказал. Иначе пусто. |
| `ensurePhysics(level, anchor)` | Мир измерения, создаётся при первом вызове с началом лаборатории в `anchor`. Событие `PhysicsWorldCreatedEvent`. |
| `soft()` | Доступ к мягким телам (`SoftBodyApi`). |
| `registerDensity(key, kgPerM3)` | Плотность для блока: ключ — путь (`"oak_planks"`, для всех пространств имён) или полный id (`"mymod:rock"`, полный id приоритетнее). Действует на новые сборки. |
| `unregisterDensity(key)` | Снимает регистрацию. `false`, если такой ключ не был зарегистрирован через API. |
| `registeredDensities()` | Копия зарегистрированных плотностей, только для чтения. |
| `densityOf(BlockState)` | Плотность, которую физика возьмёт для блока сейчас. |
| `registeredDensityCount()` | Сколько плотностей зарегистрировано через API. |
| `describe()` | Строка для логов: версия, нативы, таблица, мягкие тела. |

## PhysicsWorld (жёсткие тела)

Вид на мир одного измерения. Объект хранит только уровень, поэтому каждый вызов читает текущий мир. После закрытия мира или его отказа вызовы бросают `IllegalStateException`.

| Метод | Что делает |
|---|---|
| `level()`, `origin()` | Измерение и угол лаборатории в мировых координатах. |
| `spawnBody(BodyKind, centre)` | Тело из встроенной формы (центр масс в мировых координатах), в покое. Возвращает id. |
| `applyImpulse(id, impulse, point)` | Импульс (Н·с) в мировой точке. Синхронно. |
| `setPose(id, centre, quat)` | Перемещает тело и останавливает его. Кватернион `x, y, z, w` нормируется сам. |
| `body(id)` | `Optional<BodyInfo>`: центр, поворот, масса, скорости, границы, число частей, `assembled`. |
| `bodies()` | Все тела снимка. |
| `bodiesIn(box)` | Тела, чьи границы пересекают область (мировые координаты). |
| `nearestBody(point, radius)` | Ближайшее тело в радиусе. |
| `assemble(min, size)` | Кубом блоков → одно тело. `OptionalLong`, пусто при отмене событием. |
| `assembleCells(cells)` | То же для произвольного набора клеток. |
| `disassemble(id)` | Разборка сборки обратно в блоки (только без поворота и только для тел из блоков). |
| `setPaused(boolean)`, `isPaused()` | Пауза симуляции. |
| `setWind(vec)` | Ветер, м/с, мировые оси. |
| `stats()` | `WorldStats`: время, шаги, число тел, сборок, статических коллайдеров, время шага. |

Виды тел (`BodyKind`): `WOOD` (дуб, 1 м куб), `STONE` (камень, 1 м куб), `BOAT` (лодка), `WING` (крыло).

Сборка принимает не все блоки: воздух, жидкости, блоки с сущностями и неразрушаемые пропускаются. Лимиты мира: максимум блоков за один раз и в целом (`Assembler.MAX_ASSEMBLE_CELLS`, `MAX_PHYSICAL_CELLS`).

## SoftBodyApi (мягкие тела)

Пресеты: `rubber`, `jelly`, `cloth`, `rope` (`SoftBodyApi.PRESETS`). Лимиты: `MAX_BODIES = 16`, `MAX_PARTICLES = 5000` на тело. Мягкие тела сталкиваются только с плоскостью пола, заданной при создании, и не сталкиваются с блоками и друг с другом.

| Метод | Что делает |
|---|---|
| `spawn(preset, origin, floorY)` | Создаёт тело, возвращает id. |
| `ids()`, `count()` | Список id и число тел. |
| `info(id)` | `Optional<SoftBodyInfo>`: центр, максимальная скорость, энергия, отношение объёма, жёсткость, демпфирование, конечность. |
| `remove(id)` | Удаляет тело. |
| `pin(id, "top"\|"bottom"\|"start"\|"end")` | Закрепляет слой частиц, возвращает число. |
| `pinParticle(id, index)` | Закрепляет одну частицу. |
| `setStiffness(id, k)` | Жёсткость 0.05…100 (1.0 — пресет). |
| `setDamping(id, d)` | Демпфирование 0…50 1/с. |
| `particlePositions(id)` | Координаты `x, y, z` всех частиц, мировые, копия массива. |
| `isBroken()` | Остановлен ли солвер из-за ошибки. |

## События

Все события — подклассы `FoxPhysEvent`. Публикуются на сервере.

| Событие | Когда | Отменяемое |
|---|---|---|
| `AssembleRequestedEvent` | Перед переносом блоков в тело. Ничего не изменено. Поля: мир, запрошенные клетки. | Да: сборка отменяется, блоки остаются. |
| `BodyAssembledEvent` | Тело создано в физике, блоки убраны из мира. Поля: мир, id, число блоков. | Нет |
| `BodyDisassembledEvent` | Тело разобрано, блоки вернулись. Поля: мир, id, позиции возвращённых блоков, число выпавших. | Нет |
| `BodySpawnedEvent` | Добавлено жёсткое тело (через API и `/foxphys spawn`). Поля: мир, id, вид. | Нет |
| `PhysicsWorldCreatedEvent` | Измерению впервые выделили физический мир. Поля: мир, угол лаборатории. | Нет |
| `PhysicsWorldTickEvent` | Каждый тик сервера для каждого мира после шага. Поле: мир. | Нет |
| `SoftBodySpawnedEvent` | Создано мягкое тело. Поля: id, пресет. | Нет |
| `SoftBodyRemovedEvent` | Удалено мягкое тело. Поле: id. | Нет |

Подписка:
- NeoForge: `NeoForge.EVENT_BUS.addListener(BodySpawnedEvent.class, e -> ...)`.
- Fabric: `FoxPhysEvents.BODY_SPAWNED.register(e -> ...)`. Список полей — в `FoxPhysEvents`.

Отмена (`AssembleRequestedEvent.setCanceled(true)`) работает в обеих сборках, но порядок вызова слушателей после отмены различается:
- Fabric: вызываются все слушатели; сборка отменяется, если отменил хотя бы один.
- NeoForge: слушатели, зарегистрированные без `receiveCanceled`, после отмены не получают событие. Если нужен вызов всех, регистрируйте с `addListener(EventPriority.NORMAL, true, Class, consumer)`.

## Ограничения версии 1

- Плавучесть лодки — только по материалу (воздух внутри не учитывается).
- Регистрация плотности не пересчитывает существующие тела.
- Мягкие тела не сталкиваются с блоками и друг с другом.
- Клиентской части API нет: рендер и сетевой протокол внутренние.
- Плотности блоков в обеих сборках берутся в одном порядке: переопределение через API, затем таблица `block-physics.json` (1060 блоков), затем старая таблица материалов, затем эвристики по имени.
- Крепление мягких тел к жёстким телам, ветер и SPH-вода — внутренние системы (`SoftAttachments`, `Wind`, `FluidWater`). В API они не выставлены; команды `/foxphys soft attach|detach` и `/foxphys water sph` доступны игрокам.
- Проверено без игры: мягкие тела, плотности, события и отмена (smoke-тесты в `validation/api/` для NeoForge и Fabric; `API_SMOKE_OK` в обеих сборках). Жёсткие тела (`PhysicsWorld`, сборка, разборка, импульсы) требуют запущенного сервера и в этой поставке не проверены в игре.

## Сверка

Проверено по коду (alpha.7, ход 28): методы из этого документа есть в NeoForge и Fabric; 8 событий из таблицы «События» есть в обеих сборках. Проверка в игре не проводилась.
