package dev.foxphys.item;

import dev.foxphys.Assembler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

/**
 * Honey glue. Right-click a block: the blocks connected to it (flood fill) become one physics
 * body, and one glue is used up. A refused group keeps the glue and leaves the world unchanged.
 */
public final class HoneyGlueItem extends Item {
    public HoneyGlueItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getLevel() instanceof ServerLevel world)) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        try {
            String message = Assembler.glue(world, context.getClickedPos());
            if (!player.isCreative()) context.getItemInHand().shrink(1);
            player.displayClientMessage(Component.literal(message), true);
            return InteractionResult.SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.displayClientMessage(Component.literal("FoxPhys: " + e.getMessage()), true);
            return InteractionResult.FAIL;
        }
    }
}
