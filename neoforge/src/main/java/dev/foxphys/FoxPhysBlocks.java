package dev.foxphys;

import dev.foxphys.block.PhysicsAssemblerBlock;
import dev.foxphys.block.PhysicsAssemblerBlockEntity;
import dev.foxphys.block.PhysicsBlock;
import dev.foxphys.block.PhysicsBlockEntity;
import dev.foxphys.item.GravityGunItem;
import dev.foxphys.item.HoneyGlueItem;
import dev.foxphys.item.PhysicsWaterBucketItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration of the physics assembler, the gravity gun, honey glue and the physics block.
 *
 * Legacy ids (voxphys:*) stay registered with the same classes, so worlds and inventories saved
 * under VoxPhys still load. They are not in the creative tabs.
 *
 * NeoForge registers content on the mod bus through DeferredRegister; the objects below are
 * holders, so use {@code .get()} to reach the registered block, item or block entity type.
 */
public final class FoxPhysBlocks {
    private FoxPhysBlocks() {}

    public static final ResourceLocation ASSEMBLER_ID = ResourceLocation.fromNamespaceAndPath("foxphys", "physics_assembler");
    public static final ResourceLocation GUN_ID = ResourceLocation.fromNamespaceAndPath("foxphys", "gravity_gun");
    public static final ResourceLocation GLUE_ID = ResourceLocation.fromNamespaceAndPath("foxphys", "honey_glue");
    public static final ResourceLocation LEGACY_ASSEMBLER_ID = ResourceLocation.fromNamespaceAndPath("voxphys", "physics_assembler");
    public static final ResourceLocation LEGACY_GUN_ID = ResourceLocation.fromNamespaceAndPath("voxphys", "gravity_gun");
    public static final ResourceLocation PHYSICS_BLOCK_ID = ResourceLocation.fromNamespaceAndPath("foxphys", "physics_block");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("foxphys");
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("foxphys");
    public static final DeferredRegister.Blocks LEGACY_BLOCKS = DeferredRegister.createBlocks("voxphys");
    public static final DeferredRegister.Items LEGACY_ITEMS = DeferredRegister.createItems("voxphys");
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, "foxphys");

    private static BlockBehaviour.Properties assemblerProperties() {
        return BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops();
    }

    public static final DeferredBlock<Block> ASSEMBLER =
            BLOCKS.registerBlock("physics_assembler", PhysicsAssemblerBlock::new, assemblerProperties());
    public static final DeferredItem<?> ASSEMBLER_ITEM = ITEMS.registerSimpleBlockItem("physics_assembler", ASSEMBLER);
    public static final DeferredItem<?> GUN = ITEMS.registerItem("gravity_gun", p -> new GravityGunItem(p.stacksTo(1)));
    public static final DeferredItem<?> GLUE = ITEMS.registerItem("honey_glue", p -> new HoneyGlueItem(p.stacksTo(16)));
    /** Debug item (creative only): a bucket of physics water. */
    public static final DeferredItem<?> WATER_BUCKET = ITEMS.registerItem("physics_water_bucket", p -> new PhysicsWaterBucketItem(p.stacksTo(1)));

    /** Legacy VoxPhys ids: same behaviour, hidden from creative. */
    public static final DeferredBlock<Block> LEGACY_ASSEMBLER =
            LEGACY_BLOCKS.registerBlock("physics_assembler", PhysicsAssemblerBlock::new, assemblerProperties());
    public static final DeferredItem<?> LEGACY_ASSEMBLER_ITEM = LEGACY_ITEMS.registerSimpleBlockItem("physics_assembler", LEGACY_ASSEMBLER);
    public static final DeferredItem<?> LEGACY_GUN = LEGACY_ITEMS.registerItem("gravity_gun", p -> new GravityGunItem(p.stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PhysicsAssemblerBlockEntity>> ASSEMBLER_ENTITY =
            BLOCK_ENTITY_TYPES.register("physics_assembler", () ->
                    BlockEntityType.Builder.of(PhysicsAssemblerBlockEntity::new, ASSEMBLER.get(), LEGACY_ASSEMBLER.get()).build(null));

    /**
     * Block of an assembled body while it is in the world. Not in the creative tabs and not obtainable:
     * it exists only as a body cell. Unbreakable by hardness; players are refused in FoxPhysMod.
     */
    public static final DeferredBlock<Block> PHYSICS_BLOCK =
            BLOCKS.registerBlock("physics_block", PhysicsBlock::new,
                    BlockBehaviour.Properties.of().noOcclusion().strength(-1.0f, 3600000.0f).noLootTable());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PhysicsBlockEntity>> PHYSICS_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("physics_block", () ->
                    BlockEntityType.Builder.of(PhysicsBlockEntity::new, PHYSICS_BLOCK.get()).build(null));

    /** Connects every register to the mod bus. Call from the mod constructor. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        LEGACY_BLOCKS.register(modBus);
        LEGACY_ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
    }

    /** Creative tab entries (replaces the Fabric ItemGroupEvents). */
    public static void addCreativeEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ASSEMBLER_ITEM.get());
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(GUN.get());
            event.accept(GLUE.get());
            event.accept(WATER_BUCKET.get());
        }
    }
}
