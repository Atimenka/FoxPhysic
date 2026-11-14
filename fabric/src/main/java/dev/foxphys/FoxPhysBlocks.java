package dev.foxphys;

import dev.foxphys.block.PhysicsAssemblerBlock;
import dev.foxphys.block.PhysicsAssemblerBlockEntity;
import dev.foxphys.block.PhysicsBlock;
import dev.foxphys.block.PhysicsBlockEntity;
import dev.foxphys.item.GravityGunItem;
import dev.foxphys.item.HoneyGlueItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Registration of the physics assembler, the gravity gun and honey glue.
 *
 * Legacy ids (voxphys:*) stay registered with the same classes, so worlds and inventories saved
 * under VoxPhys still load. They are not in the creative tabs.
 */
public final class FoxPhysBlocks {
    private FoxPhysBlocks() {}

    public static final Identifier ASSEMBLER_ID = Identifier.of("foxphys", "physics_assembler");
    public static final Identifier GUN_ID = Identifier.of("foxphys", "gravity_gun");
    public static final Identifier GLUE_ID = Identifier.of("foxphys", "honey_glue");
    public static final Identifier WATER_BUCKET_ID = Identifier.of("foxphys", "physics_water_bucket");
    public static final Identifier LEGACY_ASSEMBLER_ID = Identifier.of("voxphys", "physics_assembler");
    public static final Identifier LEGACY_GUN_ID = Identifier.of("voxphys", "gravity_gun");
    public static final Identifier PHYSICS_BLOCK_ID = Identifier.of("foxphys", "physics_block");

    private static AbstractBlock.Settings assemblerSettings() {
        return AbstractBlock.Settings.create().strength(3.5f).requiresTool();
    }

    public static final Block ASSEMBLER = Registry.register(Registries.BLOCK, ASSEMBLER_ID,
            new PhysicsAssemblerBlock(assemblerSettings()));
    public static final Item ASSEMBLER_ITEM = Registry.register(Registries.ITEM, ASSEMBLER_ID,
            new BlockItem(ASSEMBLER, new Item.Settings()));
    public static final Item GUN = Registry.register(Registries.ITEM, GUN_ID,
            new GravityGunItem(new Item.Settings().maxCount(1)));
    public static final Item GLUE = Registry.register(Registries.ITEM, GLUE_ID,
            new HoneyGlueItem(new Item.Settings().maxCount(16)));

    /** Legacy VoxPhys ids: same behaviour, hidden from creative. */
    public static final Block LEGACY_ASSEMBLER = Registry.register(Registries.BLOCK, LEGACY_ASSEMBLER_ID,
            new PhysicsAssemblerBlock(assemblerSettings()));
    public static final Item LEGACY_ASSEMBLER_ITEM = Registry.register(Registries.ITEM, LEGACY_ASSEMBLER_ID,
            new BlockItem(LEGACY_ASSEMBLER, new Item.Settings()));
    public static final Item LEGACY_GUN = Registry.register(Registries.ITEM, LEGACY_GUN_ID,
            new GravityGunItem(new Item.Settings().maxCount(1)));

    public static final BlockEntityType<PhysicsAssemblerBlockEntity> ASSEMBLER_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, ASSEMBLER_ID,
            BlockEntityType.Builder.create(PhysicsAssemblerBlockEntity::new, ASSEMBLER, LEGACY_ASSEMBLER).build(null));

    /** Debug item (creative only): a bucket of SPH water. */
    public static final Item WATER_BUCKET = Registry.register(Registries.ITEM, WATER_BUCKET_ID,
            new dev.foxphys.item.PhysicsWaterBucketItem(new Item.Settings().maxCount(1)));

    /**
     * Block of an assembled body while it is in the world. Not in the creative tabs and not obtainable:
     * it exists only as a body cell. Unbreakable by hardness; players are refused in FoxPhysMod.
     */
    public static final Block PHYSICS_BLOCK = Registry.register(Registries.BLOCK, PHYSICS_BLOCK_ID,
            new PhysicsBlock(AbstractBlock.Settings.create().nonOpaque().strength(-1.0f, 3600000.0f).dropsNothing()));

    public static final BlockEntityType<PhysicsBlockEntity> PHYSICS_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, PHYSICS_BLOCK_ID,
            BlockEntityType.Builder.create(PhysicsBlockEntity::new, PHYSICS_BLOCK).build(null));

    /** Forces class initialisation (and so registration) during mod start-up. */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(ASSEMBLER_ITEM));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(GUN);
            entries.add(GLUE);
            entries.add(WATER_BUCKET);
        });
    }
}
