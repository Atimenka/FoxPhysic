# JNI ABI v1

Java class: `dev.foxphys.nativecore.NativeBridge`. Four static exports:

```java
int abiVersion();
long create(String configJson);
String exchange(long handle, String requestJson);
void destroy(long handle);
```

`handle` is a monotonically allocated integer in a mutex-protected Rust registry. It is **not a raw pointer**. Closed/unknown handles and malformed requests raise Java `IllegalStateException`. Destruction releases the entire Rapier/fluid world. Up to 8 simultaneous native worlds; 8 MiB maximum JSON request.

Configuration (other fields get explicit defaults):

```json
{"gravity":9.81,"air_density":1.225,"water_density":998.2,"step_hz":120,"max_bodies":0}
```

An exchange may contain environment replacement, commands and logical time:

```json
{
  "dt":0.05,
  "terrain":[{"center":[8,-0.5,8],"size":[16,1,16]}],
  "water_cells":[],
  "commands":[
    {"op":"add_body","body":{
      "id":1,"position":[8,5,8],"rotation":[0,0,0,1],"velocity":[0,0,0],
      "parts":[{"offset":[0,0,0],"size":[1,1,1],"density":550,"block":"minecraft:oak_planks"}]
    }},
    {"op":"set_wind","velocity":[0,0,-20]}
  ]
}
```

- All coordinates are relative to the Java world's fixed integer lab origin.
- `dt` is 0..0.1 seconds; `dt=0` applies commands without advancing physics.
- Omit `terrain` / `water_cells` to retain previous data. Empty arrays clear them.
- `terrain` contains axis-aligned collision boxes sampled from the block shapes.
- `water_cells`: `{"cell":[x,y,z],"height":1,"velocity":[0,0,0]}`. These are sampled vanilla water columns, NOT the dynamic tank state.
- `parts` offsets refer to the rigid body's model origin. Mass/COM/inertia come from all attached colliders; parts cannot overlap.
- ID is a positive 64-bit signed integer. Body count: `max_bodies` (0 = no limit, 1..10000 otherwise, default 64). The engine itself has no body cap. 64 parts/body and 256 parts/world.
- Unknown JSON fields are rejected instead of silently ignored.
- Environment sections validate before replacement. Commands are individually validated and applied **in order**, not as an all-or-nothing transaction. Check every `results[i].ok`.

Additional commands:

```json
{"op":"remove_body","id":1}
{"op":"impulse","id":1,"impulse":[3000,0,0]}
{"op":"impulse","id":1,"impulse":[0,3000,0],"point":[8.5,5,8]}
{"op":"pause","paused":true}
{"op":"set_tank","tank":{"origin":[2,4,2],"nx":12,"nz":12,"dx":1,"depth_left":3,"depth_right":0.35,"viscosity":0.001,"damping":0.04,"roughness":0}}
{"op":"remove_tank"}
{"op":"add_spring","spring":{"id":1,"a":null,"b":1,"anchor_a":[8,9,8],"anchor_b":[0,0,0],"rest_length":2,"stiffness":1000,"damping":100}}
{"op":"remove_spring","id":1}
```

The optional impulse `point` is in **lab coordinates**, while a spring's `anchor_b` is body-local and `anchor_a` is local to A or lab-space if `a=null`.

Snapshot fields match `Protocol.java`: ABI, logical seconds/fixed steps, bodies (pose, velocities, parts, mass, buoyancy/air-force diagnostics), tank depths/volume error, wind, native exchange time, number of static colliders and per-command results. Depths use row-major indexing `z*nx+x`. Timing excludes most Java sampling/render/network time and must not be presented as total frame time.

JSON is intentionally a debuggable **prototype** transport. For large moving worlds, replace it with versioned direct buffers/batched commands after a measured profile, preserving input validation and ownership. JNI should exchange snapshots, not make a call per force sample.

## Дополнение v1.1 (аддитивно, ABI не меняется)

Старые клиенты продолжают работать: новые поля необязательны, а неизвестные поля в ответе просто игнорируются.

**Блок-сборщик.** Ячейка блока: `{"cell":[x,y,z],"density":800,"friction":0.6,"restitution":0,"block":"minecraft:stone"}`; `cell` — целочисленная координата, блок занимает `[c, c+1]³`.

```json
{"op":"assemble_body","id":10,"cells":[ ...ячейки... ]}
{"op":"attach_cells","id":10,"cells":[ ...ячейки... ]}
{"op":"disassemble_body","id":10}
```

- `assemble_body` создаёт одно тело из уникальных ячеек. Дубликаты (даже с разными материалами) отвергаются.
- `attach_cells` требует выровненного по сетке тела (кратно 90°).
- `disassemble_body` удаляет тело и возвращает ячейки в **результате команды**: `results[i].cells = [[x,y,z], ...]`. Поле `cells` присутствует только при успехе разборки.
- Все команды не атомарны: проверяйте `results[i].ok`. Отказ не меняет состояние тела.

**Резервуар.** Новое поле `roughness` (коэффициент Маннинга `n`, `0..0.2`, по умолчанию `0`) включает трение о дно.

**Снимок тел.** Каждое тело содержит `aabb_min` и `aabb_max`: точные мировые границы всех коллайдеров, включая поворот (`|R|·половина размера`). Это хитбокс для presentation и взаимодействия; сами коллайдеры остаются точными боксами.

**Ошибки.** Недопустимые ячейки, дубликаты, превышение лимитов коллайдеров, наклонённое тело и неверная плотность возвращаются как `ok:false` с сообщением. Ошибки JSON верхнего уровня по-прежнему приводят к `IllegalStateException`.

## Дополнение v1.2 (аддитивно, ABI не меняется)

**Инкрементальный терраин.** `{"op":"patch_terrain","add":[{"key":K,"center":[..],"size":[..]}],"remove":[K,...]}`.
- `add` вставляет коробку под ключом `key` (u64, выбирает Java) или заменяет коробку с тем же ключом. `remove` удаляет ключи; неизвестный ключ игнорируется, поэтому патч можно безопасно повторить.
- Команда проверяется целиком до изменения. Дубликат ключа в `add`, некорректная коробка или превышение 16384 коробок отвергают команду; терраин остаётся прежним.
- Java присылает только разницу со своей картой ключей. Полная замена через поле `terrain` работает как раньше (ключи 0..n).

**Разборка с привязкой к сетке.** `{"op":"disassemble_body","id":N,"snap":true}`. Тело, которое лежит не на сетке из-за целого или дробного сдвига, округляется до целых блоков. Поворот по-прежнему обязан быть кратным 90°. Команда отказывает (тело не удаляется), если части тела имеют разные дробные сдвиги (нежёсткий сдвиг) или сдвиг ровно в полблока. Без `snap` поведение прежнее.

**Записи сборки в сохранении.** В `<мир>/foxphys/<измерение>.json` рядом с `state` появляется `assemblies`: `[{"id":N,"cells":[{"pos":[x,y,z],"block":"minecraft:..."}]}]`. Координаты относительно origin стенда. Состояние блока сохраняется только идентификатором (без свойств вроде ориентации ступеней). Файлы без `assemblies` читаются как раньше.

**Результат разборки.** `results[i].cells` содержит клетки, которые Rust занял после округления. Java сопоставляет их с записанной формой сдвигом по минимальному углу и проверяет совпадение всех клеток.

## Суставы, сохранение и настройка солвера (ABI 1, дополнение)

Все поля добавлены с значениями по умолчанию: старые запросы работают без изменений.

**Суставы.** `add_joint` / `remove_joint`. Тип `kind`:

| kind | Свободная степень | `limits` | Примечание |
| --- | --- | --- | --- |
| `hinge` | поворот вокруг `axis` | углы в радианах, `[lo, hi]` | шарнир |
| `slider` | перемещение вдоль `axis` | смещение в метрах, `[lo, hi]` | скользящее соединение |
| `cone_twist` | поворот в конусе | `[0, θ]`, используется `θ = limits[1]` | ограничения по AngY и AngZ, приближённо |
| `rope` | расстояние ≤ `max_length` | не используется | верёвка; цепь — набор верёвок |

- `a: null` — сустав привязан к миру (фиксированная точка `anchor_a` в мировых координатах).
- `axis` задаётся в локальной системе обоих тел и должен совпадать физически при создании.
- `break_force` (Н, `1..10 000 000`): сустав разрывается, если сила превышает значение. Разрыв приходит в снимке как `broken_joints: [id, ...]` один раз, в том же ответе, где произошёл.
- Снимок содержит `joint_count`.
- Тела, на которые ссылается сустав или пружина, при удалении тела удаляют и связи.

**Сохранение.** `export_state` возвращает `results[i].state` (версия формата `format: 1`): тела с позицией, поворотом, линейной и угловой скоростями, пружины, суставы, ветер. Окружение (терраин, вода, резервуар) **не** сохраняется: Java передаёт его при каждом скане.
`import_state` заменяет тела, пружины и суставы. Проверка выполняется до изменений. Если применение не удалось, мир откатывается к состоянию до вызова. Неизвестный `format` отвергается.

**Солвер.** В `config`: `solver_iterations` (1..8, по умолчанию 4), `ccd_substeps` (1..8, по умолчанию 4). Значения по умолчанию получены замерами (см. ROADMAP).

**Тела.** Угловая скорость тела хранится в `SavedState` отдельно от `Body` (в `AddBody` её нет и не требуется).

## Физический поток (JNI, ABI 1, дополнение)

Лаборатория работает через поток `foxphys-physics` (один на стенд). Мир шагает по реальному времени и не зависит от тиков сервера.

| Функция | Поведение |
| --- | --- |
| `workerCreate(config)` | создаёт поток, возвращает дескриптор |
| `workerPush(h, request)` | ставит вход в очередь и не ждёт; вход применяется между шагами, время не продвигает |
| `workerCall(h, request)` | то же, но ждёт результата до 2 с; для команд, экспорта и импорта |
| `workerLatest(h)` | последний опубликованный снимок (JSON) или `null` до первого шага; не блокирует |
| `workerTakeError(h)` | `""`, `"F:..."` (фатально: шаг не удался, стенд остановлен) или `"R:..."` (вход отклонён, мир работает); каждая ошибка сообщается один раз |
| `workerSetIdle(h, idle)` | простой: 10 Гц, шаг до 0.1 с. Java ставит стенд на паузу (`pause`), когда рядом нет игроков, поэтому в простое тела не движутся |
| `workerDestroy(h)` | останавливает поток и ждёт его завершения |

Догонка: в активном режиме не более 4 фиксированных шагов за цикл, поэтому при перегрузке симуляция замедляется, а не накапливает долг.

## Мягкие тела, крепление, ветер и SPH-пулы (ABI 1, дополнение alpha.7)

ABI не меняется. Добавлены два JSON-входа: `NativeBridge.softExchange(String) -> String` (мягкие тела, `soft_world.rs`) и `NativeBridge.fluidExchange(String) -> String` (SPH-пулы, `fluid_world.rs`). Каждый запрос — объект с полем `op`. Ошибка разбора, неизвестная операция (`unknown soft op 'x'`) или отказ мира не возвращают JSON: JNI выбрасывает исключение Java (`fail`), а вызывающий код (`SoftBodies`, `FluidWater`) перехватывает его и пишет в лог.

### Мягкие тела (`softExchange`)

| `op` | Поля запроса | Ответ |
|---|---|---|
| `spawn` | `preset` (rubber, jelly, cloth, rope), `origin` [x,y,z], `floor` (высота пола) | `id`, `particles` |
| `step` | `dt`, `substeps` | — |
| `status` | `id` | центр, `vmax`, `volume_ratio_min/max`, `stiffness`, `damping`, `preset` |
| `positions` | `id`, `tris` (по умолчанию `true`; `false` — без поверхности) | `pos` (x,y,z на частицу, метры, округление до 1 мм; в мировых координатах, как `origin` при `spawn`), `tris` (индексы поверхностных треугольников, замкнутая поверхность; поверхность считается один раз при спавне), `preset` |
| `list` | — | `bodies` (id) |
| `pin` | `id`, `side` (top, bottom, start, end) | `pinned` |
| `anchor` | `id`, `side` | `indices` закреплённых частиц (мягкая фиксация с запоминанием массы) |
| `anchor_targets` | `id`, `targets`: массив `[index, x, y, z]` | — (отказ, если частица не закреплена) |
| `release` | `id` | `released` (число частиц; масса возвращается) |
| `wind` | `velocity` [x,y,z] | `wind` (компоненты ±60 м/с) |
| `stiffness`, `damping` | `id`, `value` | — |
| `remove` | `id` | — |

Ветер применяется ко всем телам как аэродинамическое сопротивление на частицу. Крепление держит частицы в целях, которые Java вычисляет из позы жёсткого тела (`SoftAttachments`).

### SPH-пулы (`fluidExchange`)

| `op` | Поля запроса | Ответ |
|---|---|---|
| `create` | `size` [x,y,z], `fill_height`, `dx`, `shape` (`still`, `dam`) | `id`, `particles` |
| `step` | `id`, `seconds`, `max_steps` (до 400) | `steps`, `advanced`, `requested`, `sim_time` |
| `positions` | `id` | `n` (число частиц), `xyz` (координаты относительно якоря, плоский список) |
| `status` | `id` | `particles`, `size`, `dx`, `fill_height`, `shape`, `mass`, `mean_density`, `kinetic_energy`, `top`, `sim_time` |
| `remove` | `id` | — |
| `list` | — | `pools` (id) |

Шаг по времени выбирает решатель (`stable_dt`, условие CFL и силы). Поле `seconds` — физическое время, `max_steps` ограничивает число подшагов за вызов.

### Сетевые пакеты (Java, канал `foxphys:...`)

| Пакет | Направление | Содержимое |
|---|---|---|
| `soft_meshes` (`SoftMeshes`) | сервер → клиент, раз в тик | для каждого мягкого тела: id, пресет, позиции и треугольники. Треугольники приходят полностью при появлении тела и раз в 20 тиков; в остальные тики список пустой, и клиент берёт поверхность из своего кэша по id |
| `fluid_frame` (`FluidFrame`) | сервер → клиент, раз в тик | для каждого пула рядом с игроком: id, якорь, `dx`, позиции |
| `align_request` (`AlignRequest`) | клиент → сервер | нажата клавиша R с гравипушкой в руке (без данных) |

Пакеты кодируются одинаково в обеих сборках (`FriendlyByteBuf` и `RegistryByteBuf`/`PacketCodec`). Регистрация: NeoForge `RegisterPayloadHandlersEvent`, Fabric `PayloadTypeRegistry`.
