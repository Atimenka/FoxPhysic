#!/usr/bin/env python3
"""Build the full Minecraft 1.21.1 block physics table.

Inputs
  --dump  TSV produced by the BlockDump harness (game values, default block state):
          id, hardness, blast, sound, full_cube, fluid, block_entity,
          tool_required, map_color, solid, air, class
Outputs
  --csv   machine-readable table (one row per block)
  --md    human-readable Markdown with summary and full table

Game values (taken from the running game, not estimated):
  hardness  = BlockState.getDestroySpeed (-1 = unbreakable)
  blast     = Block.getExplosionResistance (Java Edition values)

Estimated values (NOT game data, labelled 'оценка' in the output):
  density   = bulk material density in kg/m^3, assigned by material rule
  fragility = score 0..1 (1 = shatters on impact), assigned by material class
"""
import argparse
import csv
import re
from collections import Counter

# material key -> (density kg/m^3, fragility class, label RU)
# Densities are typical bulk values of the real-world material; fragility
# classes are qualitative. See docs/BLOCK_PHYSICS.md for the rationale.
MATERIALS = {
    "air":        (1.225, "none",     "воздух"),
    "water":      (1000,  "fluid",    "вода"),
    "lava":       (3100,  "fluid",    "лава (значение ТЗ 3100; в старом файле 2800, не проверено)"),
    "stone":      (2650,  "stone",    "камень / магматическая порода"),
    "deepslate":  (2850,  "stone",    "глубинный сланец"),
    "basalt":     (2900,  "stone",    "базальт / блэкстоун"),
    "sandstone":  (2200,  "stone",    "песчаник"),
    "endstone":   (2700,  "stone",    "камень Края"),
    "netherrack": (2600,  "stone",    "адский камень"),
    "brick":      (1900,  "stone",    "кирпич"),
    "concrete":   (2400,  "stone",    "бетон"),
    "terracotta": (2000,  "stone",    "терракота"),
    "prismarine": (2700,  "stone",    "призмарин"),
    "quartz":     (2650,  "stone",    "кварц"),
    "clay":       (1700,  "granular", "глина"),
    "ore":        (3000,  "stone",    "руда (смесь с породой)"),
    "coal_ore":   (1350,  "stone",    "угольный блок / уголь"),
    "glass":      (2500,  "brittle",  "стекло"),
    "amethyst":   (2650,  "brittle",  "аметист"),
    "obsidian":   (2450,  "brittle",  "обсидиан"),
    "ice":        (917,   "brittle",  "лёд"),
    "blue_ice":   (950,   "brittle",  "синий лёд"),
    "snow_powder":(100,   "granular", "снег (порошок)"),
    "snow_block": (500,   "granular", "снежный блок"),
    "sand":       (1600,  "granular", "песок"),
    "gravel":     (1800,  "granular", "гравий"),
    "soul_sand":  (1500,  "granular", "песок душ"),
    "dirt":       (1500,  "granular", "земля"),
    "concrete_powder": (1500, "granular", "бетонный порошок"),
    "wood":       (700,   "wood",     "древесина"),
    "wood_light": (450,   "wood",     "светлая древесина (ель/джунгли)"),
    "wood_hard":  (750,   "wood",     "твёрдая древесина"),
    "nether_wood":(600,   "wood",     "адская древесина"),
    "leaves":     (200,   "organic",  "листва"),
    "plant":      (150,   "organic",  "растение"),
    "wool":       (350,   "elastic",  "шерсть"),
    "sponge":     (150,   "elastic",  "губка"),
    "slime":      (1200,  "elastic",  "слизь"),
    "honey":      (1400,  "elastic",  "мёд"),
    "honeycomb":  (600,   "elastic",  "соты"),
    "composite":  (1100,  "wood",     "композит (шалкер, сборные блоки)"),
    "cobweb":     (100,   "elastic",  "паутина"),
    "iron":       (7874,  "metal",    "железо"),
    "gold":       (19300, "metal",    "золото"),
    "copper":     (8960,  "metal",    "медь"),
    "netherite":  (8000,  "metal",    "древний металл (netherite)"),
    "lead_metal": (11340, "metal",    "свинец-подобный (ancient)"),
    "diamond":    (3515,  "brittle",  "алмаз"),
    "emerald":    (2760,  "brittle",  "изумруд"),
    "lapis":      (2800,  "brittle",  "ляпис"),
    "redstone":   (2400,  "brittle",  "редстоун"),
    "tnt":        (1600,  "granular", "TNT"),
    "bedrock":    (2650,  "stone",    "бедрок (неразрушим; в моде масса задаётся отдельно)"),
    "unknown":    (1000,  "stone",    "не классифицировано (заглушка 1000)"),
}

FRAGILITY_SCORE = {
    "none": 0.0, "fluid": 0.0, "brittle": 0.9, "stone": 0.6,
    "granular": 0.2, "wood": 0.3, "organic": 0.3, "elastic": 0.05, "metal": 0.1,
}
FRAGILITY_LABEL = {
    "none": "нет", "fluid": "жидкость", "brittle": "хрупкий", "stone": "каменный",
    "granular": "сыпучий", "wood": "волокнистый", "organic": "мягкий органический",
    "elastic": "упругий", "metal": "пластичный",
}

WOOD_SPECIES = {
    "spruce": "wood_light", "jungle": "wood_light", "cherry": "wood_light",
    "birch": "wood", "oak": "wood", "acacia": "wood", "dark_oak": "wood_hard",
    "mangrove": "wood", "bamboo": "wood_light", "crimson": "nether_wood",
    "warped": "nether_wood", "pale_oak": "wood_hard",
}

# Checked BEFORE RULES (specific families that generic rules would mislabel).
PRIORITY_RULES = [
    (r"^copper_ore$|^deepslate_copper_ore$", "ore"),
    (r"coral_block$", "stone"),
    (r"banner$", "wool"),
    (r"shulker_box$", "composite"),
    (r"candle_cake$|^cake$", "wood"),
    (r"^coarse_dirt$", "dirt"),
    (r"^piston_head$|^chest$|^trapped_chest$|^carved_pumpkin$|^barrel$|^lectern$|^campfire$|^soul_campfire$|^composter$|^daylight_detector$|^observer$", "WOOD"),
    (r"mushroom_block$", "wood_light"),
    (r"^nether_wart_block$", "nether_wood"),
    (r"^brewing_stand$|^crafter$", "iron"),
    (r"^beacon$", "glass"),
    (r"froglight$|^sniffer_egg$", "composite"),
    (r"^decorated_pot$", "terracotta"),
    (r"^calibrated_sculk_sensor$", "clay"),
]

# Ordered rules: first match wins. (regex on the id without namespace, material key)
RULES = [
    (r"^air$|^cave_air$|^void_air$|^structure_void$|^moving_piston$|^light$", "air"),
    (r"^water$|^bubble_column$|^seagrass$|^tall_seagrass$|^kelp", "water"),
    (r"^lava$|^lava_cauldron$", "lava"),
    (r"^.*_cauldron$", "wood"),           # cauldrons are iron-like; overridden below
    (r"glass", "glass"),
    (r"^obsidian$|^crying_obsidian$|^respawn_anchor$", "obsidian"),
    (r"^blue_ice$", "blue_ice"),
    (r"^packed_ice$|^ice$|^frosted_ice$", "ice"),
    (r"^snow_block$", "snow_block"),
    (r"^snow$|^powder_snow$", "snow_powder"),
    (r"^diamond_", "diamond"),
    (r"^emerald_", "emerald"),
    (r"^lapis_", "lapis"),
    (r"^redstone_(block|ore|lamp)|^deepslate_redstone_ore$", "redstone"),
    (r"^coal_(ore|block)$|^deepslate_coal_ore$", "coal_ore"),
    (r"amethyst", "amethyst"),
    (r"^netherite_block$|^ancient_debris$", "netherite"),
    (r"^gold_|^raw_gold|^nether_gold_ore$|^deepslate_gold_ore$|^gilded_blackstone$", "gold"),
    (r"^copper_|^raw_copper|^(waxed_)?(weathered|oxidized|exposed)?_?copper|cut_copper|copper_(grate|bulb|chiseled)|chiseled_copper|^waxed_.*copper", "copper"),
    (r"^iron_(bars|door|trapdoor|block|chain)$|^iron_block$|^raw_iron|^chain$|^anvil$|^damaged_anvil$|^chipped_anvil$|^hopper$|^cauldron$|^lantern$|^soul_lantern$|^bell$|^heavy_core$", "iron"),
    (r"^iron_ore$|^deepslate_iron_ore$|^copper_ore$|^deepslate_copper_ore$", "ore"),
    (r"_ore$", "ore"),
    (r"^sculk", "clay"),
    (r"^honey_block$", "honey"),
    (r"^honeycomb_block$", "honeycomb"),
    (r"^slime_block$", "slime"),
    (r"^sponge$|^wet_sponge$", "sponge"),
    (r"_wool$|^wool$|carpet$", "wool"),
    (r"terracotta$", "terracotta"),
    (r"^concrete_powder|_concrete_powder$", "concrete_powder"),
    (r"concrete$", "concrete"),
    (r"^end_stone|^end_stone_bricks$|^purpur", "endstone"),
    (r"^basalt|^polished_basalt$|^smooth_basalt$|^blackstone|polished_blackstone|gilded_blackstone|^deepslate_bricks|^deepslate$|^deepslate_tiles$|^chiseled_deepslate$|^cobbled_deepslate|^polished_deepslate|^reinforced_deepslate|^infested_deepslate", "deepslate"),
    (r"^netherrack$|^nether_bricks$|^red_nether_bricks$|nether_brick|^crimson_nylium$|^warped_nylium$|^nether_quartz_ore$|^magma_block$|soul_soil$", "netherrack"),
    (r"^quartz|^smooth_quartz$|^quartz_", "quartz"),
    (r"prismarine", "prismarine"),
    (r"sandstone", "sandstone"),
    (r"^sand$|^red_sand$|^suspicious_sand$", "sand"),
    (r"^gravel$|^suspicious_gravel$", "gravel"),
    (r"^soul_sand$", "soul_sand"),
    (r"^clay$|^mud$|^packed_mud$|^mud_brick|^muddy_mangrove_roots$|^mangrove_roots$", "clay"),
    (r"^dirt|^grass_block$|^podzol$|^mycelium$|^farmland$|^rooted_dirt$|^moss_block$|^moss_carpet$", "dirt"),
    (r"^tnt$", "tnt"),
    (r"^bedrock$", "bedrock"),
    (r"^(stone|cobblestone|granite|diorite|andesite|tuff|calcite|dripstone|smooth_stone|stone_brick|mossy_|cracked_|chiseled_|polished_|infested_|brick|bricks|pointed_dripstone|smooth|cut_|sea_lantern|stonecutter|grindstone|bone_block|dripstone|deepslate)", "stone"),
    (r"^(leaves|.*_leaves|azalea_leaves|flowering_azalea_leaves)$", "leaves"),
    (r"^(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped|pale_oak)", "WOOD"),
    (r"^(stripped_|petrified_)", "WOOD"),
    (r"^(hay_block|dried_kelp_block|sugar_cane|cactus|melon|pumpkin|jack_o_lantern)$", "wood"),
    (r"(_log|_wood|_stem|_hyphae|_planks|_fence|_sign|_door|_trapdoor|_button|_pressure_plate|_slab|_stairs|_boat|_chest|_bookshelf|_table|_ladder|_shelf|_lectern|_barrel|_composter|_bee_nest|_beehive|_crafting_table|_chest_boat|_post|_bed)$", "WOOD"),
    (r"^(leaves|vine|moss|azalea|flower|tulip|orchid|poppy|dandelion|allium|rose|peony|lilac|sunflower|fern|grass|short_grass|tall_grass|seagrass|kelp|sapling|mushroom|fungus|roots|vines|nether_sprouts|spore|sweet_berry|lily|cornflower|torchflower|pitcher|wheat|potato|carrot|beetroot|melon_stem|pumpkin_stem|cocoa|bamboo_sapling|big_dripleaf|small_dripleaf|glow_lichen|hanging_roots|frogspawn|sea_pickle|turtle_egg|coral|dead_bush|bush|lichen|stem|crop)", "plant"),
    (r"^(honey_block)$", "honey"),
    (r"^(redstone_(wire|torch))$|^redstone_torch$", "redstone"),
]

# Overrides applied by exact id (takes priority over RULES).
OVERRIDES = {
    "minecraft:cauldron": "iron",
    "minecraft:water_cauldron": "iron",
    "minecraft:lava_cauldron": "iron",
    "minecraft:powder_snow_cauldron": "iron",
    "minecraft:lantern": "iron",
    "minecraft:soul_lantern": "iron",
    "minecraft:sea_lantern": "stone",
    "minecraft:bookshelf": "WOOD",
    "minecraft:tnt": "tnt",
    "minecraft:obsidian": "obsidian",
    "minecraft:end_portal_frame": "stone",
    "minecraft:bee_nest": "WOOD",
    "minecraft:beehive": "WOOD",
    "minecraft:tuff": "stone",
    "minecraft:calcite": "stone",
    "minecraft:amethyst_block": "amethyst",
    "minecraft:budding_amethyst": "amethyst",
    "minecraft:tinted_glass": "glass",
    "minecraft:glowstone": "glass",
    "minecraft:shroomlight": "honeycomb",
    "minecraft:dragon_egg": "stone",
    "minecraft:sculk": "clay",
    "minecraft:sculk_catalyst": "clay",
    "minecraft:sculk_shrieker": "clay",
    "minecraft:sculk_sensor": "clay",
    "minecraft:sculk_vein": "plant",
    "minecraft:ender_chest": "obsidian",
    "minecraft:end_stone": "endstone",
    "minecraft:hay_block": "wood",
    "minecraft:straw": "wood",
    "minecraft:dirt_path": "dirt",
    "minecraft:lodestone": "iron",
    "minecraft:crafting_table": "WOOD",
    "minecraft:furnace": "stone",
    "minecraft:blast_furnace": "stone",
    "minecraft:smoker": "stone",
    "minecraft:dispenser": "stone",
    "minecraft:dropper": "stone",
    "minecraft:piston": "WOOD",
    "minecraft:sticky_piston": "WOOD",
    "minecraft:jukebox": "WOOD",
    "minecraft:note_block": "WOOD",
    "minecraft:redstone_lamp": "glass",
    "minecraft:target": "wood",
    "minecraft:tripwire_hook": "iron",
    "minecraft:lightning_rod": "iron",
    "minecraft:spawner": "iron",
    "minecraft:trial_spawner": "iron",
    "minecraft:vault": "iron",
    "minecraft:heavy_core": "iron",
    "minecraft:scaffolding": "WOOD",
    "minecraft:copper_block": "copper",
    "minecraft:raw_iron_block": "iron",
    "minecraft:raw_copper_block": "copper",
    "minecraft:raw_gold_block": "gold",
    "minecraft:netherite_block": "netherite",
    "minecraft:iron_block": "iron",
    "minecraft:gold_block": "gold",
    "minecraft:diamond_block": "diamond",
    "minecraft:emerald_block": "emerald",
    "minecraft:lapis_block": "lapis",
    "minecraft:redstone_block": "redstone",
    "minecraft:coal_block": "coal_ore",
    "minecraft:honey_block": "honey",
    "minecraft:honeycomb_block": "honeycomb",
    "minecraft:slime_block": "slime",
    "minecraft:bone_block": "stone",
    "minecraft:packed_ice": "ice",
    "minecraft:blue_ice": "blue_ice",
    "minecraft:quartz_block": "quartz",
    "minecraft:quartz_bricks": "quartz",
    "minecraft:quartz_pillar": "quartz",
    "minecraft:chiseled_quartz_block": "quartz",
    "minecraft:smooth_quartz": "quartz",
    "minecraft:nether_quartz_ore": "netherrack",
    "minecraft:glass_pane": "glass",
    "minecraft:iron_bars": "iron",
    "minecraft:chain": "iron",
    "minecraft:grindstone": "stone",
    "minecraft:stonecutter": "stone",
    "minecraft:anvil": "iron",
    "minecraft:chipped_anvil": "iron",
    "minecraft:damaged_anvil": "iron",
    "minecraft:loom": "WOOD",
    "minecraft:cartography_table": "WOOD",
    "minecraft:fletching_table": "WOOD",
    "minecraft:smithing_table": "WOOD",
    "minecraft:enchanting_table": "obsidian",
    "minecraft:respawn_anchor": "obsidian",
    "minecraft:end_gateway": "endstone",
    "minecraft:end_portal": "air",
    "minecraft:nether_portal": "air",
    "minecraft:fire": "air",
    "minecraft:soul_fire": "air",
    "minecraft:end_rod": "wood",
    "minecraft:torch": "wood",
    "minecraft:wall_torch": "wood",
    "minecraft:soul_torch": "wood",
    "minecraft:ladder": "WOOD",
    "minecraft:scaffolding_": "WOOD",
    "minecraft:cobweb": "cobweb",
    "minecraft:bedrock": "bedrock",
    "minecraft:command_block": "stone",
    "minecraft:repeating_command_block": "stone",
    "minecraft:chain_command_block": "stone",
    "minecraft:structure_block": "stone",
    "minecraft:jigsaw": "stone",
    "minecraft:barrier": "stone",
    "minecraft:light": "air",
}


def classify(block_id: str, hardness: float, blast: float, is_air: bool,
             is_fluid: bool, is_solid: bool):
    """Return (material_key, rule_source)."""
    name = block_id.split(":", 1)[1]
    if block_id in OVERRIDES:
        key = OVERRIDES[block_id]
        src = "override"
    else:
        key, src = None, None
        for pattern, mat in PRIORITY_RULES + RULES:
            if re.search(pattern, name):
                key, src = mat, "rule"
                break
        if key is None:
            key, src = None, "fallback"
    if key is None:
        if is_air:
            key = "air"
        elif is_fluid:
            key = "water"
        elif is_solid:
            key = "unknown"
        else:
            key = "plant"
        src = "fallback"

    if key == "WOOD":
        # Pick species density/fragility from the id prefix.
        name = block_id.split(":", 1)[1]
        key = "wood"
        for sp, wkey in WOOD_SPECIES.items():
            if name.startswith(sp) or name.startswith("stripped_" + sp) or name.startswith("petrified_" + sp):
                key = wkey
                break
        src = "rule"
    return key, src


def build(dump_path: str):
    rows = []
    with open(dump_path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < len(header):
                continue
            rec = dict(zip(header, p))
            if rec["hardness"] == "ERROR":
                continue
            block_id = rec["id"]
            hardness = float(rec["hardness"])
            blast = float(rec["blast"])
            is_air = rec["air"] == "true"
            is_fluid = rec["fluid"] == "true"
            is_solid = rec["solid"] == "true"
            key, src = classify(block_id, hardness, blast, is_air, is_fluid, is_solid)
            density, frag_cls, label = MATERIALS[key]
            if block_id in ("minecraft:bedrock",):
                density_note = "условно (игровое значение не физическое)"
            else:
                density_note = "оценка"
            frag = FRAGILITY_SCORE[frag_cls]
            if key == "unknown":
                frag_cls = "stone"
                frag = FRAGILITY_SCORE["stone"]
            # Hardness -1 means unbreakable in game (bedrock, barrier etc.)
            rows.append({
                "id": block_id.replace("minecraft:", ""),
                "hardness_game": hardness,
                "blast_game": blast,
                "density_kg_m3": density,
                "density_source": density_note,
                "material": label,
                "material_key": key,
                "fragility_score": frag,
                "fragility_class": FRAGILITY_LABEL[frag_cls],
                "fluid": rec["fluid"] == "true",
                "full_cube": rec["full_cube"] == "true",
                "solid": is_solid,
                "block_entity": rec["block_entity"] == "true",
                "tool_required": rec["tool_required"] == "true",
                "rule": src,
            })
    return rows


def write_csv(rows, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def write_json(rows, path):
    """One entry per block, keyed by the full id. The mod reads density and fragility from here."""
    import json
    blocks = {}
    for r in rows:
        key = "minecraft:" + r["id"]
        blocks[key] = {
            "density": r["density_kg_m3"],
            "fragility": r["fragility_score"],
            "material": r["material_key"],
            "hardness": r["hardness_game"],
            "blast": r["blast_game"],
        }
    doc = {
        "_about": ("Свойства всех 1060 блоков Minecraft 1.21.1 (состояние по умолчанию). hardness и blast — "
                   "значения игры. density (кг/м^3) и fragility (0..1) — оценки по материалу; см. docs/BLOCK_PHYSICS.md. "
                   "Генератор: scripts/blocks/build_block_table.py."),
        "blocks": blocks,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=0, sort_keys=True)
        f.write("\n")


def fmt_density(d):
    if d >= 1e6:
        return f"{d:.3e}"
    return f"{d:g}"


def write_md(rows, path):
    total = len(rows)
    by_src = Counter(r["rule"] for r in rows)
    by_mat = Counter(r["material"] for r in rows)
    unknown = [r["id"] for r in rows if r["material_key"] == "unknown"]
    lines = []
    lines.append("# Физические свойства блоков Minecraft 1.21.1\n")
    lines.append("Источник твёрдости (`hardness`) и взрывоустойчивости (`blast`) — игра: "
                 "`BlockState.getDestroySpeed` и `Block.getExplosionResistance` для состояния по умолчанию, "
                 "дамп 1060 блоков (`scripts/blocks/blocks-dump.tsv`, харнесс `BlockDump`, Minecraft 1.21.1 Mojang-имена).\n")
    lines.append("**Плотность и хрупкость — оценки, а не игровые данные.** В Minecraft нет свойства плотности, "
                 "поэтому плотность назначена по материалу (типичные объёмные значения реальных веществ), "
                 "а хрупкость — по классу материала (шкала 0…1, 1 = разлетается при ударе). "
                 "Игровых данных для этих двух колонок не существует; датапак с ними найти не удалось.\n")
    lines.append(f"Всего блоков: **{total}**. Назначено по правилам: {by_src.get('rule',0)}, "
                 f"по переопределению: {by_src.get('override',0)}, по запасному варианту: {by_src.get('fallback',0)}. "
                 f"Не классифицировано: {len(unknown)}.\n")
    lines.append("## Классы материалов (плотность — оценка)\n")
    lines.append("| Ключ | Материал | Плотность, кг/м³ | Класс хрупкости | Балл хрупкости |")
    lines.append("|---|---|---|---|---|")
    for k, (d, fc, lab) in MATERIALS.items():
        lines.append(f"| {k} | {lab} | {fmt_density(d)} | {FRAGILITY_LABEL[fc]} | {FRAGILITY_SCORE[fc]} |")
    lines.append("\n## Таблица по блокам\n")
    lines.append("| Блок | Твёрдость (игра) | Взрывоустойчивость (игра) | Плотность, кг/м³ (оценка) | Материал | Хрупкость (класс) | Балл | Жидкость | Полный куб | Тип |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        lines.append("| {id} | {h:g} | {b:g} | {d} | {m} | {fc} | {fs} | {fl} | {fu} | {rule} |".format(
            id=r["id"], h=r["hardness_game"], b=r["blast_game"],
            d=fmt_density(r["density_kg_m3"]), m=r["material"], fc=r["fragility_class"],
            fs=r["fragility_score"], fl="да" if r["fluid"] else "", fu="да" if r["full_cube"] else "",
            rule=r["rule"]))
    if unknown:
        lines.append("\n## Не классифицированные блоки (заглушка плотности 1000)\n")
        lines.append(", ".join(unknown))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return by_mat


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--csv", required=True)
    ap.add_argument("--md", required=True)
    ap.add_argument("--json", help="per-block JSON for the mod (assets/foxphys/block-physics.json)")
    a = ap.parse_args()
    rows = build(a.dump)
    write_csv(rows, a.csv)
    mats = write_md(rows, a.md)
    if a.json:
        write_json(rows, a.json)
    print("rows", len(rows))
    for k, v in mats.most_common():
        print(f"{v:5d}  {k}")
