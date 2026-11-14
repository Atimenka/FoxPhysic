package dev.foxphys.item;

import dev.foxphys.Assembler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

/**
 * Honey glue. Right-click a block: the blocks connected to it (flood fill) become one physics
 * body, and one glue is used up. A refused group keeps the glue and leaves the world unchanged.
 */
public final class HoneyGlueItem extends Item {
    public HoneyGlueItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient()) return ActionResult.SUCCESS;
        if (!(context.getWorld() instanceof ServerWorld world)) return ActionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayerEntity player)) return ActionResult.PASS;
        try {
            String message = Assembler.glue(world, context.getBlockPos());
            if (!player.isCreative()) context.getStack().decrement(1);
            player.sendMessage(Text.literal(message), true);
            return ActionResult.SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
            return ActionResult.FAIL;
        }
    }
}
