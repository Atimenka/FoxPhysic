package dev.foxphys.item;

import dev.foxphys.FoxPhysMod;
import dev.foxphys.fluid.FluidPool;
import dev.foxphys.fluid.FluidWater;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Debug item: a bucket of SPH water. Right-click on a block pours a pool of still water on the clicked face;
 * sneak + right-click pours a dam-break column instead. Creative only.
 *
 * The water is 3D particle SPH (see the fluid package). It does not yet float or push rigid bodies.
 */
public final class PhysicsWaterBucketItem extends Item {
    public PhysicsWaterBucketItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel world)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        boolean dam = player != null && player.isShiftKeyDown();
        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        try {
            FluidPool pool = FluidWater.add(world, origin, dam);
            if (player != null) {
                player.displayClientMessage(Component.literal(dam
                        ? "Ведро SPH-воды: прорыв плотины, " + pool.particles() + " частиц"
                        : "Ведро SPH-воды: спокойная вода, " + pool.particles() + " частиц"), true);
            }
        } catch (RuntimeException e) {
            FoxPhysMod.LOGGER.warn("Water bucket failed", e);
            if (player != null) player.displayClientMessage(Component.literal("FoxPhys: " + e.getMessage()), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Air: pour at the player's feet, so the bucket works without a block target.
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(level instanceof ServerLevel world)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        boolean dam = player.isShiftKeyDown();
        try {
            FluidPool pool = FluidWater.add(world, BlockPos.containing(player.position()).above(2), dam);
            player.displayClientMessage(Component.literal("Ведро SPH-воды: " + pool.particles() + " частиц над головой"), true);
        } catch (RuntimeException e) {
            player.displayClientMessage(Component.literal("FoxPhys: " + e.getMessage()), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
