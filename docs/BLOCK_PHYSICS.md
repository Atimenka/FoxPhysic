# Physical properties of Minecraft 1.21.1 blocks

The source of hardness (`hardness`) and blast resistance (`blast`) is the game: `BlockState.getDestroySpeed` and `Block.getExplosionResistance` for the default state, a dump of 1060 blocks (`scripts/blocks/blocks-dump.tsv`, harness `BlockDump`, Minecraft 1.21.1 Mojang names).

**Density and fragility are estimates, not game data.** Minecraft has no density or fragility property. Density is assigned per material using typical bulk values of real substances; fragility is assigned per material class (scale 0…1, where 1 means highly brittle). Treat both as tuning estimates, not canonical values.

Total blocks: **1060**. Assigned by rule: 840, by override: 93, by fallback: 127. Unclassified: 0.

## Material classes (density is an estimate)

| Key | Material | Density, kg/m³ | Fragility class | Fragility score |
|---|---|---|---|---|
| air | air | 1.225 | none | 0.0 |
| water | water | 1000 | fluid | 0.0 |
| lava | lava | 3100 | fluid | 0.0 |
| stone | stone / igneous rock | 2650 | stone-like | 0.6 |
| deepslate | deepslate | 2850 | stone-like | 0.6 |
| basalt | basalt / blackstone | 2900 | stone-like | 0.6 |
| sandstone | sandstone | 2200 | stone-like | 0.6 |
| endstone | End stone | 2700 | stone-like | 0.6 |
| netherrack | netherrack | 2600 | stone-like | 0.6 |
| brick | brick | 1900 | stone-like | 0.6 |
| concrete | concrete | 2400 | stone-like | 0.6 |
| terracotta | terracotta | 2000 | stone-like | 0.6 |
| prismarine | prismarine | 2700 | stone-like | 0.6 |
| quartz | quartz | 2650 | stone-like | 0.6 |
| clay | clay | 1700 | granular | 0.2 |
| ore | ore (mixed with host rock) | 3000 | stone-like | 0.6 |
| coal_ore | coal block / coal | 1350 | stone-like | 0.6 |
| glass | glass | 2500 | brittle | 0.9 |
| amethyst | amethyst | 2650 | brittle | 0.9 |
| obsidian | obsidian | 2450 | brittle | 0.9 |
| ice | ice | 917 | brittle | 0.9 |
| blue_ice | blue ice | 950 | brittle | 0.9 |
| snow_powder | snow (powder) | 100 | granular | 0.2 |
| snow_block | snow block | 500 | granular | 0.2 |
| sand | sand | 1600 | granular | 0.2 |
| gravel | gravel | 1800 | granular | 0.2 |
| soul_sand | soul sand | 1500 | granular | 0.2 |
| dirt | dirt | 1500 | granular | 0.2 |
| concrete_powder | concrete powder | 1500 | granular | 0.2 |
| wood | wood | 700 | fibrous | 0.3 |
| wood_light | light wood (spruce/jungle) | 450 | fibrous | 0.3 |
| wood_hard | hard wood | 750 | fibrous | 0.3 |
| nether_wood | nether wood | 600 | fibrous | 0.3 |
| leaves | leaves | 200 | soft organic | 0.3 |
| plant | plant | 150 | soft organic | 0.3 |
| wool | wool | 350 | elastic | 0.05 |
| sponge | sponge | 150 | elastic | 0.05 |
| slime | slime | 1200 | elastic | 0.05 |
| honey | honey | 1400 | elastic | 0.05 |
| honeycomb | honeycomb | 600 | elastic | 0.05 |
| composite | composite (shulker, composite blocks) | 1100 | fibrous | 0.3 |
| cobweb | cobweb | 100 | elastic | 0.05 |
| iron | iron | 7874 | ductile metal | 0.1 |
| gold | gold | 19300 | ductile metal | 0.1 |
| copper | copper | 8960 | ductile metal | 0.1 |
| netherite | netherite (ancient metal) | 8000 | ductile metal | 0.1 |
| lead_metal | lead-like (ancient) | 11340 | ductile metal | 0.1 |
| diamond | diamond | 3515 | brittle | 0.9 |
| emerald | emerald | 2760 | brittle | 0.9 |
| lapis | lapis lazuli | 2800 | brittle | 0.9 |
| redstone | redstone | 2400 | brittle | 0.9 |
| tnt | TNT | 1600 | granular | 0.2 |
| bedrock | bedrock (indestructible; the mass is set separately in the mod) | 2650 | stone-like | 0.6 |
| unknown | unclassified (placeholder 1000) | 1000 | stone-like | 0.6 |

## Table by block

| Block | Hardness (game) | Blast resistance (game) | Density, kg/m³ (estimate) | Material | Fragility (class) | Score | Fluid | Full cube | Type |
|---|---|---|---|---|---|---|---|---|---|
| air | 0 | 0 | 1.225 | air | none | 0.0 |  |  | rule |
| stone | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| granite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| polished_granite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| diorite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| polished_diorite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| andesite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| polished_andesite | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| grass_block | 0.6 | 0.6 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| dirt | 0.5 | 0.5 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| coarse_dirt | 0.5 | 0.5 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| podzol | 0.5 | 0.5 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| cobblestone | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| oak_planks | 2 | 3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| spruce_planks | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| birch_planks | 2 | 3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| jungle_planks | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| acacia_planks | 2 | 3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| cherry_planks | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| dark_oak_planks | 2 | 3 | 750 | hard wood | fibrous | 0.3 |  | yes | rule |
| mangrove_planks | 2 | 3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| bamboo_planks | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| bamboo_mosaic | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| oak_sapling | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_sapling | 0 | 0 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_sapling | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_sapling | 0 | 0 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_sapling | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_sapling | 0 | 0 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_sapling | 0 | 0 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_propagule | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bedrock | -1 | 3.6e+06 | 2650 | bedrock (indestructible; the mass is set separately in the mod) | stone-like | 0.6 |  | yes | override |
| water | 100 | 100 | 1000 | water | fluid | 0.0 | yes |  | rule |
| lava | 100 | 100 | 3100 | lava | fluid | 0.0 | yes |  | rule |
| sand | 0.5 | 0.5 | 1600 | sand | granular | 0.2 |  | yes | rule |
| suspicious_sand | 0.25 | 0.25 | 1600 | sand | granular | 0.2 |  | yes | rule |
| red_sand | 0.5 | 0.5 | 1600 | sand | granular | 0.2 |  | yes | rule |
| gravel | 0.6 | 0.6 | 1800 | gravel | granular | 0.2 |  | yes | rule |
| suspicious_gravel | 0.25 | 0.25 | 1800 | gravel | granular | 0.2 |  | yes | rule |
| gold_ore | 3 | 3 | 19300 | gold | ductile metal | 0.1 |  | yes | rule |
| deepslate_gold_ore | 4.5 | 3 | 19300 | gold | ductile metal | 0.1 |  | yes | rule |
| iron_ore | 3 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| deepslate_iron_ore | 4.5 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| coal_ore | 3 | 3 | 1350 | coal block / coal | stone-like | 0.6 |  | yes | rule |
| deepslate_coal_ore | 4.5 | 3 | 1350 | coal block / coal | stone-like | 0.6 |  | yes | rule |
| nether_gold_ore | 3 | 3 | 19300 | gold | ductile metal | 0.1 |  | yes | rule |
| oak_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| spruce_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| birch_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| jungle_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| acacia_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| cherry_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| dark_oak_log | 2 | 2 | 750 | hard wood | fibrous | 0.3 |  | yes | rule |
| mangrove_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| mangrove_roots | 0.7 | 0.7 | 1700 | clay | granular | 0.2 |  | yes | rule |
| muddy_mangrove_roots | 0.7 | 0.7 | 1700 | clay | granular | 0.2 |  | yes | rule |
| bamboo_block | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_spruce_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_birch_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_jungle_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_acacia_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_cherry_log | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_dark_oak_log | 2 | 2 | 750 | hard wood | fibrous | 0.3 |  | yes | rule |
| stripped_oak_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_mangrove_log | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_bamboo_block | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| oak_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| spruce_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| birch_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| jungle_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| acacia_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| cherry_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| dark_oak_wood | 2 | 2 | 750 | hard wood | fibrous | 0.3 |  | yes | rule |
| mangrove_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_oak_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_spruce_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_birch_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_jungle_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_acacia_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stripped_cherry_wood | 2 | 2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| stripped_dark_oak_wood | 2 | 2 | 750 | hard wood | fibrous | 0.3 |  | yes | rule |
| stripped_mangrove_wood | 2 | 2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| oak_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| spruce_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| birch_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| jungle_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| acacia_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| cherry_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| dark_oak_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| mangrove_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| azalea_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| flowering_azalea_leaves | 0.2 | 0.2 | 200 | leaves | soft organic | 0.3 |  | yes | rule |
| sponge | 0.6 | 0.6 | 150 | sponge | elastic | 0.05 |  | yes | rule |
| wet_sponge | 0.6 | 0.6 | 150 | sponge | elastic | 0.05 |  | yes | rule |
| glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| lapis_ore | 3 | 3 | 2800 | lapis lazuli | brittle | 0.9 |  | yes | rule |
| deepslate_lapis_ore | 4.5 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| lapis_block | 3 | 3 | 2800 | lapis lazuli | brittle | 0.9 |  | yes | override |
| dispenser | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| chiseled_sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| cut_sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| note_block | 0.8 | 0.8 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| white_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| orange_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| magenta_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| light_blue_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| yellow_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| lime_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| pink_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| gray_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| light_gray_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cyan_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| purple_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| blue_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| brown_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| green_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| red_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| black_bed | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| powered_rail | 0.7 | 0.7 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| detector_rail | 0.7 | 0.7 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| sticky_piston | 1.5 | 1.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| cobweb | 4 | 4 | 100 | cobweb | elastic | 0.05 |  |  | override |
| short_grass | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| fern | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| dead_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| seagrass | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | rule |
| tall_seagrass | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | rule |
| piston | 1.5 | 1.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| piston_head | 1.5 | 1.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| white_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| orange_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| magenta_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| light_blue_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| yellow_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| lime_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| pink_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| gray_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| light_gray_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| cyan_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| purple_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| blue_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| brown_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| green_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| red_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| black_wool | 0.8 | 0.8 | 350 | wool | elastic | 0.05 |  | yes | rule |
| moving_piston | -1 | 0 | 1.225 | air | none | 0.0 |  |  | rule |
| dandelion | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| torchflower | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| poppy | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| blue_orchid | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| allium | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| azure_bluet | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| red_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| orange_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| white_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| pink_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| oxeye_daisy | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| cornflower | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| wither_rose | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| lily_of_the_valley | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| brown_mushroom | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| red_mushroom | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| gold_block | 3 | 6 | 19300 | gold | ductile metal | 0.1 |  | yes | override |
| iron_block | 5 | 6 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| bricks | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| tnt | 0 | 0 | 1600 | TNT | granular | 0.2 |  | yes | override |
| bookshelf | 1.5 | 1.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| chiseled_bookshelf | 1.5 | 1.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| mossy_cobblestone | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| obsidian | 50 | 1200 | 2450 | obsidian | brittle | 0.9 |  | yes | override |
| torch | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | override |
| wall_torch | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | override |
| fire | 0 | 0 | 1.225 | air | none | 0.0 |  |  | override |
| soul_fire | 0 | 0 | 1.225 | air | none | 0.0 |  |  | override |
| spawner | 5 | 5 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| oak_stairs | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| chest | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| redstone_wire | 0 | 0 | 2400 | redstone | brittle | 0.9 |  |  | rule |
| diamond_ore | 3 | 3 | 3515 | diamond | brittle | 0.9 |  | yes | rule |
| deepslate_diamond_ore | 4.5 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| diamond_block | 5 | 6 | 3515 | diamond | brittle | 0.9 |  | yes | override |
| crafting_table | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| wheat | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| farmland | 0.6 | 0.6 | 1500 | dirt | granular | 0.2 |  |  | rule |
| furnace | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| oak_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| acacia_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| jungle_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_sign | 1 | 1 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| oak_door | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| ladder | 0.4 | 0.4 | 700 | wood | fibrous | 0.3 |  |  | rule |
| rail | 0.7 | 0.7 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| cobblestone_stairs | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| oak_wall_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_wall_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_wall_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| acacia_wall_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_wall_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| jungle_wall_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_wall_sign | 1 | 1 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_wall_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_wall_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| oak_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| acacia_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| jungle_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_hanging_sign | 1 | 1 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| crimson_hanging_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_hanging_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| mangrove_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| oak_wall_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_wall_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_wall_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| acacia_wall_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_wall_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| jungle_wall_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_wall_hanging_sign | 1 | 1 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_wall_hanging_sign | 1 | 1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| crimson_wall_hanging_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_wall_hanging_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| bamboo_wall_hanging_sign | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| lever | 0.5 | 0.5 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| stone_pressure_plate | 0.5 | 0.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| iron_door | 5 | 5 | 7874 | iron | ductile metal | 0.1 |  |  | rule |
| oak_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_pressure_plate | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_pressure_plate | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_pressure_plate | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_pressure_plate | 0.5 | 0.5 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_pressure_plate | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| redstone_ore | 3 | 3 | 2400 | redstone | brittle | 0.9 |  | yes | rule |
| deepslate_redstone_ore | 4.5 | 3 | 2400 | redstone | brittle | 0.9 |  | yes | rule |
| redstone_torch | 0 | 0 | 2400 | redstone | brittle | 0.9 |  |  | rule |
| redstone_wall_torch | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| stone_button | 0.5 | 0.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| snow | 0.1 | 0.1 | 100 | snow (powder) | granular | 0.2 |  |  | rule |
| ice | 0.5 | 0.5 | 917 | ice | brittle | 0.9 |  | yes | rule |
| snow_block | 0.2 | 0.2 | 500 | snow block | granular | 0.2 |  | yes | rule |
| cactus | 0.4 | 0.4 | 700 | wood | fibrous | 0.3 |  |  | rule |
| clay | 0.6 | 0.6 | 1700 | clay | granular | 0.2 |  | yes | rule |
| sugar_cane | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jukebox | 2 | 6 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| oak_fence | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| netherrack | 0.4 | 0.4 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| soul_sand | 0.5 | 0.5 | 1500 | soul sand | granular | 0.2 |  |  | rule |
| soul_soil | 0.5 | 0.5 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| basalt | 1.25 | 4.2 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| polished_basalt | 1.25 | 4.2 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| soul_torch | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | override |
| soul_wall_torch | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| glowstone | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | override |
| nether_portal | -1 | 0 | 1.225 | air | none | 0.0 |  |  | override |
| carved_pumpkin | 1 | 1 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| jack_o_lantern | 1 | 1 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| repeater | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| white_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| orange_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| magenta_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| light_blue_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| yellow_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| lime_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| pink_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| gray_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| light_gray_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| cyan_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| purple_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| blue_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| brown_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| green_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| red_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| black_stained_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| oak_trapdoor | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_trapdoor | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_trapdoor | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_trapdoor | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_trapdoor | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_trapdoor | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_trapdoor | 3 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_trapdoor | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_trapdoor | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| stone_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| mossy_stone_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| cracked_stone_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| chiseled_stone_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| packed_mud | 1 | 3 | 1700 | clay | granular | 0.2 |  | yes | rule |
| mud_bricks | 1.5 | 3 | 1700 | clay | granular | 0.2 |  | yes | rule |
| infested_stone | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_cobblestone | 1 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_stone_bricks | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_mossy_stone_bricks | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_cracked_stone_bricks | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_chiseled_stone_bricks | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| brown_mushroom_block | 0.2 | 0.2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| red_mushroom_block | 0.2 | 0.2 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  | yes | rule |
| mushroom_stem | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| iron_bars | 5 | 6 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| chain | 5 | 6 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | override |
| pumpkin | 1 | 1 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| melon | 1 | 1 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| attached_pumpkin_stem | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| attached_melon_stem | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| pumpkin_stem | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| melon_stem | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| vine | 0.2 | 0.2 | 150 | plant | soft organic | 0.3 |  |  | rule |
| glow_lichen | 0.2 | 0.2 | 150 | plant | soft organic | 0.3 |  |  | rule |
| oak_fence_gate | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| brick_stairs | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| stone_brick_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mud_brick_stairs | 1.5 | 3 | 1700 | clay | granular | 0.2 |  |  | rule |
| mycelium | 0.6 | 0.6 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| lily_pad | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| nether_bricks | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| nether_brick_fence | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| nether_brick_stairs | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| nether_wart | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| enchanting_table | 5 | 1200 | 2450 | obsidian | brittle | 0.9 |  |  | override |
| brewing_stand | 0.5 | 0.5 | 7874 | iron | ductile metal | 0.1 |  |  | rule |
| cauldron | 2 | 2 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| water_cauldron | 2 | 2 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| lava_cauldron | 2 | 2 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| powder_snow_cauldron | 2 | 2 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| end_portal | -1 | 3.6e+06 | 1.225 | air | none | 0.0 |  |  | override |
| end_portal_frame | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | override |
| end_stone | 3 | 9 | 2700 | End stone | stone-like | 0.6 |  | yes | override |
| dragon_egg | 3 | 9 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | override |
| redstone_lamp | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | override |
| cocoa | 0.2 | 3 | 150 | plant | soft organic | 0.3 |  |  | rule |
| sandstone_stairs | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| emerald_ore | 3 | 3 | 2760 | emerald | brittle | 0.9 |  | yes | rule |
| deepslate_emerald_ore | 4.5 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| ender_chest | 22.5 | 600 | 2450 | obsidian | brittle | 0.9 |  |  | override |
| tripwire_hook | 0 | 0 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| tripwire | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| emerald_block | 5 | 6 | 2760 | emerald | brittle | 0.9 |  | yes | override |
| spruce_stairs | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_stairs | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_stairs | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| command_block | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| beacon | 3 | 3 | 2500 | glass | brittle | 0.9 |  | yes | rule |
| cobblestone_wall | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mossy_cobblestone_wall | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| flower_pot | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| potted_torchflower | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_oak_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_spruce_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_birch_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_jungle_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_acacia_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_cherry_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_dark_oak_sapling | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_mangrove_propagule | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_fern | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_dandelion | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_poppy | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_blue_orchid | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_allium | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_azure_bluet | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_red_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_orange_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_white_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_pink_tulip | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_oxeye_daisy | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_cornflower | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_lily_of_the_valley | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_wither_rose | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_red_mushroom | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_brown_mushroom | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_dead_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_cactus | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| carrots | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| potatoes | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| oak_button | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_button | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_button | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_button | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_button | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_button | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_button | 0.5 | 0.5 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_button | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_button | 0.5 | 0.5 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| skeleton_skull | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| skeleton_wall_skull | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| wither_skeleton_skull | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| wither_skeleton_wall_skull | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| zombie_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| zombie_wall_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| player_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| player_wall_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| creeper_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| creeper_wall_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| dragon_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| dragon_wall_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| piglin_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| piglin_wall_head | 1 | 1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| anvil | 5 | 1200 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| chipped_anvil | 5 | 1200 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| damaged_anvil | 5 | 1200 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| trapped_chest | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| light_weighted_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| heavy_weighted_pressure_plate | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| comparator | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| daylight_detector | 0.2 | 0.2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| redstone_block | 5 | 6 | 2400 | redstone | brittle | 0.9 |  | yes | override |
| nether_quartz_ore | 3 | 3 | 2600 | netherrack | stone-like | 0.6 |  | yes | override |
| hopper | 3 | 4.8 | 7874 | iron | ductile metal | 0.1 |  |  | rule |
| quartz_block | 0.8 | 0.8 | 2650 | quartz | stone-like | 0.6 |  | yes | override |
| chiseled_quartz_block | 0.8 | 0.8 | 2650 | quartz | stone-like | 0.6 |  | yes | override |
| quartz_pillar | 0.8 | 0.8 | 2650 | quartz | stone-like | 0.6 |  | yes | override |
| quartz_stairs | 0.8 | 0.8 | 2650 | quartz | stone-like | 0.6 |  |  | rule |
| activator_rail | 0.7 | 0.7 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| dropper | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| white_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| orange_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| magenta_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| light_blue_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| yellow_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| lime_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| pink_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| gray_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| light_gray_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| cyan_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| purple_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| blue_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| brown_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| green_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| red_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| black_terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| white_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| orange_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| magenta_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| light_blue_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| yellow_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| lime_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| pink_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| gray_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| light_gray_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| cyan_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| purple_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| blue_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| brown_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| green_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| red_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| black_stained_glass_pane | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  |  | rule |
| acacia_stairs | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_stairs | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_stairs | 2 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_stairs | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_stairs | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| bamboo_mosaic_stairs | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| slime_block | 0 | 0 | 1200 | slime | elastic | 0.05 |  | yes | override |
| barrier | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| light | -1 | 3.6e+06 | 1.225 | air | none | 0.0 |  |  | override |
| iron_trapdoor | 5 | 5 | 7874 | iron | ductile metal | 0.1 |  |  | rule |
| prismarine | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  | yes | rule |
| prismarine_bricks | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  | yes | rule |
| dark_prismarine | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  | yes | rule |
| prismarine_stairs | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| prismarine_brick_stairs | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| dark_prismarine_stairs | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| prismarine_slab | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| prismarine_brick_slab | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| dark_prismarine_slab | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| sea_lantern | 0.3 | 0.3 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| hay_block | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  | yes | override |
| white_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| orange_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| magenta_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_blue_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| yellow_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| lime_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| pink_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| gray_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_gray_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| cyan_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| purple_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| blue_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| brown_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| green_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| red_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| black_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| terracotta | 1.25 | 4.2 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| coal_block | 5 | 6 | 1350 | coal block / coal | stone-like | 0.6 |  | yes | override |
| packed_ice | 0.5 | 0.5 | 917 | ice | brittle | 0.9 |  | yes | override |
| sunflower | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| lilac | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| rose_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| peony | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| tall_grass | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| large_fern | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| white_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| orange_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| magenta_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_blue_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| yellow_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| lime_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| pink_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| gray_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_gray_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| cyan_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| purple_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| blue_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| brown_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| green_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| red_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| black_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| white_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| orange_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| magenta_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_blue_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| yellow_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| lime_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| pink_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| gray_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| light_gray_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| cyan_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| purple_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| blue_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| brown_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| green_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| red_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| black_wall_banner | 1 | 1 | 350 | wool | elastic | 0.05 |  |  | rule |
| red_sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| chiseled_red_sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| cut_red_sandstone | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| red_sandstone_stairs | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| oak_slab | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| spruce_slab | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_slab | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_slab | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_slab | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_slab | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_slab | 2 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_slab | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_slab | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| bamboo_mosaic_slab | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| stone_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| smooth_stone_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| cut_sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| petrified_oak_slab | 2 | 6 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cobblestone_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| brick_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| stone_brick_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mud_brick_slab | 1.5 | 3 | 1700 | clay | granular | 0.2 |  |  | rule |
| nether_brick_slab | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| quartz_slab | 2 | 6 | 2650 | quartz | stone-like | 0.6 |  |  | rule |
| red_sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| cut_red_sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| purpur_slab | 2 | 6 | 2700 | End stone | stone-like | 0.6 |  |  | rule |
| smooth_stone | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| smooth_sandstone | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| smooth_quartz | 2 | 6 | 2650 | quartz | stone-like | 0.6 |  | yes | override |
| smooth_red_sandstone | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  | yes | rule |
| spruce_fence_gate | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_fence_gate | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_fence_gate | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_fence_gate | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_fence_gate | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_fence_gate | 2 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_fence_gate | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_fence_gate | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| spruce_fence | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_fence | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_fence | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_fence | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_fence | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_fence | 2 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_fence | 2 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_fence | 2 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| spruce_door | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| birch_door | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| jungle_door | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| acacia_door | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cherry_door | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| dark_oak_door | 3 | 3 | 750 | hard wood | fibrous | 0.3 |  |  | rule |
| mangrove_door | 3 | 3 | 700 | wood | fibrous | 0.3 |  |  | rule |
| bamboo_door | 3 | 3 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| end_rod | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | override |
| chorus_plant | 0.4 | 0.4 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| chorus_flower | 0.4 | 0.4 | 150 | plant | soft organic | 0.3 |  | yes | fallback |
| purpur_block | 1.5 | 6 | 2700 | End stone | stone-like | 0.6 |  | yes | rule |
| purpur_pillar | 1.5 | 6 | 2700 | End stone | stone-like | 0.6 |  | yes | rule |
| purpur_stairs | 1.5 | 6 | 2700 | End stone | stone-like | 0.6 |  |  | rule |
| end_stone_bricks | 3 | 9 | 2700 | End stone | stone-like | 0.6 |  | yes | rule |
| torchflower_crop | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| pitcher_crop | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| pitcher_plant | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| beetroots | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| dirt_path | 0.65 | 0.65 | 1500 | dirt | granular | 0.2 |  |  | override |
| end_gateway | -1 | 3.6e+06 | 2700 | End stone | stone-like | 0.6 |  |  | override |
| repeating_command_block | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| chain_command_block | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| frosted_ice | 0.5 | 0.5 | 917 | ice | brittle | 0.9 |  | yes | rule |
| magma_block | 0.5 | 0.5 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| nether_wart_block | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| red_nether_bricks | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| bone_block | 2 | 2 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| structure_void | 0 | 0 | 1.225 | air | none | 0.0 |  |  | rule |
| observer | 3 | 3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| white_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| orange_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| magenta_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| light_blue_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| yellow_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| lime_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| pink_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| gray_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| light_gray_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| cyan_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| purple_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| blue_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| brown_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| green_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| red_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| black_shulker_box | 2 | 2 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| white_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| orange_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| magenta_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| light_blue_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| yellow_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| lime_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| pink_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| gray_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| light_gray_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| cyan_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| purple_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| blue_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| brown_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| green_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| red_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| black_glazed_terracotta | 1.4 | 1.4 | 2000 | terracotta | stone-like | 0.6 |  | yes | rule |
| white_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| orange_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| magenta_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| light_blue_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| yellow_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| lime_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| pink_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| gray_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| light_gray_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| cyan_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| purple_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| blue_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| brown_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| green_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| red_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| black_concrete | 1.8 | 1.8 | 2400 | concrete | stone-like | 0.6 |  | yes | rule |
| white_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| orange_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| magenta_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| light_blue_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| yellow_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| lime_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| pink_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| gray_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| light_gray_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| cyan_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| purple_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| blue_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| brown_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| green_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| red_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| black_concrete_powder | 0.5 | 0.5 | 1500 | concrete powder | granular | 0.2 |  | yes | rule |
| kelp | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | rule |
| kelp_plant | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | rule |
| dried_kelp_block | 0.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| turtle_egg | 0.5 | 0.5 | 150 | plant | soft organic | 0.3 |  |  | rule |
| sniffer_egg | 0.5 | 0.5 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  |  | rule |
| dead_tube_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| dead_brain_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| dead_bubble_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| dead_fire_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| dead_horn_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| tube_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| brain_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| bubble_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| fire_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| horn_coral_block | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| dead_tube_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_brain_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_bubble_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_fire_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_horn_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| tube_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| brain_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| bubble_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| fire_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| horn_coral | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_tube_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_brain_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_bubble_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_fire_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_horn_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| tube_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| brain_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| bubble_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| fire_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| horn_coral_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_tube_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_brain_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_bubble_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_fire_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| dead_horn_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| tube_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| brain_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| bubble_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| fire_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| horn_coral_wall_fan | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| sea_pickle | 0 | 0 | 150 | plant | soft organic | 0.3 | yes |  | rule |
| blue_ice | 2.8 | 2.8 | 950 | blue ice | brittle | 0.9 |  | yes | override |
| conduit | 3 | 3 | 1000 | water | fluid | 0.0 | yes |  | fallback |
| bamboo_sapling | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| bamboo | 1 | 1 | 450 | light wood (spruce/jungle) | fibrous | 0.3 |  |  | rule |
| potted_bamboo | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| void_air | 0 | 0 | 1.225 | air | none | 0.0 |  |  | rule |
| cave_air | 0 | 0 | 1.225 | air | none | 0.0 |  |  | rule |
| bubble_column | 0 | 0 | 1000 | water | fluid | 0.0 | yes |  | rule |
| polished_granite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| smooth_red_sandstone_stairs | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| mossy_stone_brick_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_diorite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mossy_cobblestone_stairs | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| end_stone_brick_stairs | 3 | 9 | 2700 | End stone | stone-like | 0.6 |  |  | rule |
| stone_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| smooth_sandstone_stairs | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| smooth_quartz_stairs | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| granite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| andesite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| red_nether_brick_stairs | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| polished_andesite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| diorite_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_granite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| smooth_red_sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| mossy_stone_brick_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_diorite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mossy_cobblestone_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| end_stone_brick_slab | 3 | 9 | 2700 | End stone | stone-like | 0.6 |  |  | rule |
| smooth_sandstone_slab | 2 | 6 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| smooth_quartz_slab | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| granite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| andesite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| red_nether_brick_slab | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| polished_andesite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| diorite_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| brick_wall | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| prismarine_wall | 1.5 | 6 | 2700 | prismarine | stone-like | 0.6 |  |  | rule |
| red_sandstone_wall | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| mossy_stone_brick_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| granite_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| stone_brick_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| mud_brick_wall | 1.5 | 3 | 1700 | clay | granular | 0.2 |  |  | rule |
| nether_brick_wall | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| andesite_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| red_nether_brick_wall | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  |  | rule |
| sandstone_wall | 0.8 | 0.8 | 2200 | sandstone | stone-like | 0.6 |  |  | rule |
| end_stone_brick_wall | 3 | 9 | 2700 | End stone | stone-like | 0.6 |  |  | rule |
| diorite_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| scaffolding | 0 | 0 | 700 | wood | fibrous | 0.3 |  |  | rule |
| loom | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| barrel | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| smoker | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| blast_furnace | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| cartography_table | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| fletching_table | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| grindstone | 2 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | override |
| lectern | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| smithing_table | 2.5 | 2.5 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| stonecutter | 3.5 | 3.5 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | override |
| bell | 5 | 5 | 7874 | iron | ductile metal | 0.1 |  |  | rule |
| lantern | 3.5 | 3.5 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| soul_lantern | 3.5 | 3.5 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| campfire | 2 | 2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| soul_campfire | 2 | 2 | 700 | wood | fibrous | 0.3 |  |  | rule |
| sweet_berry_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| warped_stem | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| stripped_warped_stem | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| warped_hyphae | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| stripped_warped_hyphae | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| warped_nylium | 0.4 | 0.4 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| warped_fungus | 0 | 0 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_wart_block | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| warped_roots | 0 | 0 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| nether_sprouts | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| crimson_stem | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| stripped_crimson_stem | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| crimson_hyphae | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| stripped_crimson_hyphae | 2 | 2 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| crimson_nylium | 0.4 | 0.4 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| crimson_fungus | 0 | 0 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| shroomlight | 1 | 1 | 600 | honeycomb | elastic | 0.05 |  | yes | override |
| weeping_vines | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| weeping_vines_plant | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| twisting_vines | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| twisting_vines_plant | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| crimson_roots | 0 | 0 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_planks | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| warped_planks | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  | yes | rule |
| crimson_slab | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_slab | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_pressure_plate | 0.5 | 0.5 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_pressure_plate | 0.5 | 0.5 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_fence | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_fence | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_trapdoor | 3 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_trapdoor | 3 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_fence_gate | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_fence_gate | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_stairs | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_stairs | 2 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_button | 0.5 | 0.5 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_button | 0.5 | 0.5 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_door | 3 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_door | 3 | 3 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| crimson_wall_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| warped_wall_sign | 1 | 1 | 600 | nether wood | fibrous | 0.3 |  |  | rule |
| structure_block | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| jigsaw | -1 | 3.6e+06 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| composter | 0.6 | 0.6 | 700 | wood | fibrous | 0.3 |  |  | rule |
| target | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  | yes | override |
| bee_nest | 0.3 | 0.3 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| beehive | 0.6 | 0.6 | 700 | wood | fibrous | 0.3 |  | yes | rule |
| honey_block | 0 | 0 | 1400 | honey | elastic | 0.05 |  |  | override |
| honeycomb_block | 0.6 | 0.6 | 600 | honeycomb | elastic | 0.05 |  | yes | override |
| netherite_block | 50 | 1200 | 8000 | netherite (ancient metal) | ductile metal | 0.1 |  | yes | override |
| ancient_debris | 30 | 1200 | 8000 | netherite (ancient metal) | ductile metal | 0.1 |  | yes | rule |
| crying_obsidian | 50 | 1200 | 2450 | obsidian | brittle | 0.9 |  | yes | rule |
| respawn_anchor | 50 | 1200 | 2450 | obsidian | brittle | 0.9 |  | yes | override |
| potted_crimson_fungus | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_warped_fungus | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_crimson_roots | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_warped_roots | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| lodestone | 3.5 | 3.5 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| blackstone | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| blackstone_stairs | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| blackstone_wall | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| blackstone_slab | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| polished_blackstone_bricks | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| cracked_polished_blackstone_bricks | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| chiseled_polished_blackstone | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| polished_blackstone_brick_slab | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_brick_stairs | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_brick_wall | 1.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| gilded_blackstone | 1.5 | 6 | 19300 | gold | ductile metal | 0.1 |  | yes | rule |
| polished_blackstone_stairs | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_slab | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_pressure_plate | 0.5 | 0.5 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_button | 0.5 | 0.5 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_blackstone_wall | 2 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| chiseled_nether_bricks | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| cracked_nether_bricks | 2 | 6 | 2600 | netherrack | stone-like | 0.6 |  | yes | rule |
| quartz_bricks | 0.8 | 0.8 | 2650 | quartz | stone-like | 0.6 |  | yes | override |
| candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| white_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| orange_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| magenta_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| light_blue_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| yellow_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| lime_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| pink_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| gray_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| light_gray_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| cyan_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| purple_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| blue_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| brown_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| green_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| red_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| black_candle | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| white_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| orange_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| magenta_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| light_blue_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| yellow_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| lime_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| pink_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| gray_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| light_gray_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| cyan_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| purple_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| blue_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| brown_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| green_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| red_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| black_candle_cake | 0.5 | 0.5 | 700 | wood | fibrous | 0.3 |  |  | rule |
| amethyst_block | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  | yes | override |
| budding_amethyst | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  | yes | override |
| amethyst_cluster | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  |  | rule |
| large_amethyst_bud | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  |  | rule |
| medium_amethyst_bud | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  |  | rule |
| small_amethyst_bud | 1.5 | 1.5 | 2650 | amethyst | brittle | 0.9 |  |  | rule |
| tuff | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| tuff_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| tuff_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| tuff_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_tuff | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| polished_tuff_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_tuff_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| polished_tuff_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| chiseled_tuff | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| tuff_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| tuff_brick_slab | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| tuff_brick_stairs | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| tuff_brick_wall | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| chiseled_tuff_bricks | 1.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| calcite | 0.75 | 0.75 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | override |
| tinted_glass | 0.3 | 0.3 | 2500 | glass | brittle | 0.9 |  | yes | override |
| powder_snow | 0.25 | 0.25 | 100 | snow (powder) | granular | 0.2 |  |  | rule |
| sculk_sensor | 1.5 | 1.5 | 1700 | clay | granular | 0.2 |  |  | override |
| calibrated_sculk_sensor | 1.5 | 1.5 | 1700 | clay | granular | 0.2 |  |  | rule |
| sculk | 0.2 | 0.2 | 1700 | clay | granular | 0.2 |  | yes | override |
| sculk_vein | 0.2 | 0.2 | 150 | plant | soft organic | 0.3 |  |  | override |
| sculk_catalyst | 3 | 3 | 1700 | clay | granular | 0.2 |  | yes | override |
| sculk_shrieker | 3 | 3 | 1700 | clay | granular | 0.2 |  |  | override |
| copper_block | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | override |
| exposed_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| weathered_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| oxidized_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| copper_ore | 3 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| deepslate_copper_ore | 4.5 | 3 | 3000 | ore (mixed with host rock) | stone-like | 0.6 |  | yes | rule |
| oxidized_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| weathered_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| exposed_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| oxidized_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| weathered_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| exposed_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_weathered_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_exposed_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_chiseled_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| oxidized_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| weathered_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| exposed_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| oxidized_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| weathered_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| exposed_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_copper_block | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_weathered_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_exposed_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_weathered_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_exposed_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_cut_copper | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_weathered_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_exposed_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_cut_copper_stairs | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_oxidized_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_weathered_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_exposed_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_cut_copper_slab | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| exposed_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| oxidized_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| weathered_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_exposed_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_oxidized_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_weathered_copper_door | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| exposed_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| oxidized_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| weathered_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_exposed_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_oxidized_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| waxed_weathered_copper_trapdoor | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  |  | rule |
| copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| exposed_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| weathered_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| oxidized_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_exposed_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_weathered_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_copper_grate | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| exposed_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| weathered_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| oxidized_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_exposed_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_weathered_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| waxed_oxidized_copper_bulb | 3 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | rule |
| lightning_rod | 3 | 6 | 7874 | iron | ductile metal | 0.1 |  |  | override |
| pointed_dripstone | 1.5 | 3 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| dripstone_block | 1.5 | 1 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| cave_vines | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| cave_vines_plant | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| spore_blossom | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| azalea | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| flowering_azalea | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| moss_carpet | 0.1 | 0.1 | 350 | wool | elastic | 0.05 |  |  | rule |
| pink_petals | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| moss_block | 0.1 | 0.1 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| big_dripleaf | 0.1 | 0.1 | 150 | plant | soft organic | 0.3 |  |  | rule |
| big_dripleaf_stem | 0.1 | 0.1 | 700 | wood | fibrous | 0.3 |  |  | rule |
| small_dripleaf | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| hanging_roots | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| rooted_dirt | 0.5 | 0.5 | 1500 | dirt | granular | 0.2 |  | yes | rule |
| mud | 0.5 | 0.5 | 1700 | clay | granular | 0.2 |  |  | rule |
| deepslate | 3 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| cobbled_deepslate | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| cobbled_deepslate_stairs | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| cobbled_deepslate_slab | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| cobbled_deepslate_wall | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_deepslate | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| polished_deepslate_stairs | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_deepslate_slab | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| polished_deepslate_wall | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  |  | rule |
| deepslate_tiles | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| deepslate_tile_stairs | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| deepslate_tile_slab | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| deepslate_tile_wall | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| deepslate_bricks | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| deepslate_brick_stairs | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| deepslate_brick_slab | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| deepslate_brick_wall | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  |  | rule |
| chiseled_deepslate | 3.5 | 6 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| cracked_deepslate_bricks | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| cracked_deepslate_tiles | 3.5 | 6 | 2650 | stone / igneous rock | stone-like | 0.6 |  | yes | rule |
| infested_deepslate | 1.5 | 0.75 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| smooth_basalt | 1.25 | 4.2 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| raw_iron_block | 5 | 6 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| raw_copper_block | 5 | 6 | 8960 | copper | ductile metal | 0.1 |  | yes | override |
| raw_gold_block | 5 | 6 | 19300 | gold | ductile metal | 0.1 |  | yes | override |
| potted_azalea_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| potted_flowering_azalea_bush | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | fallback |
| ochre_froglight | 0.3 | 0.3 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| verdant_froglight | 0.3 | 0.3 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| pearlescent_froglight | 0.3 | 0.3 | 1100 | composite (shulker, composite blocks) | fibrous | 0.3 |  | yes | rule |
| frogspawn | 0 | 0 | 150 | plant | soft organic | 0.3 |  |  | rule |
| reinforced_deepslate | 55 | 1200 | 2850 | deepslate | stone-like | 0.6 |  | yes | rule |
| decorated_pot | 0 | 0 | 2000 | terracotta | stone-like | 0.6 |  |  | rule |
| crafter | 1.5 | 3.5 | 7874 | iron | ductile metal | 0.1 |  | yes | rule |
| trial_spawner | 50 | 50 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| vault | 50 | 50 | 7874 | iron | ductile metal | 0.1 |  | yes | override |
| heavy_core | 10 | 1200 | 7874 | iron | ductile metal | 0.1 |  |  | override |
