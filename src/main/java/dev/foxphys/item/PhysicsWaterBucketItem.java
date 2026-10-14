package dev.foxphys.item;

import dev.foxphys.FoxPhysMod;
import dev.foxphys.fluid.FluidPool;
import dev.foxphys.fluid.FluidWater;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * Debug item: a bucket of SPH water. Right-click on a block pours a pool of still water on the clicked face;
 * sneak + right-click pours a dam-break column instead. Creative only.
 *
 * The water is 3D particle SPH (see the fluid package). It does not yet float or push rigid bodies.
 */
public final class PhysicsWaterBucketItem extends Item {
    public PhysicsWaterBucketItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (world.isClient() || !(world instanceof ServerWorld server)) {
            return ActionResult.SUCCESS;
        }
        PlayerEntity player = context.getPlayer();
        boolean dam = player != null && player.isSneaking();
        BlockPos origin = context.getBlockPos().offset(context.getSide());
        try {
            FluidPool pool = FluidWater.add(server, origin, dam);
            if (player != null) {
                player.sendMessage(Text.literal(dam
                        ? "Ведро SPH-воды: прорыв плотины, " + pool.particles() + " частиц"
                        : "Ведро SPH-воды: спокойная вода, " + pool.particles() + " частиц"), true);
            }
        } catch (RuntimeException e) {
            FoxPhysMod.LOGGER.warn("Water bucket failed", e);
            if (player != null) player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        // Air: pour above the player's head, so the bucket works without a block target.
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient() || !(world instanceof ServerWorld server)) {
            return TypedActionResult.success(stack, world.isClient());
        }
        boolean dam = player.isSneaking();
        try {
            FluidPool pool = FluidWater.add(server, BlockPos.ofFloored(player.getPos()).up(2), dam);
            player.sendMessage(Text.literal("Ведро SPH-воды: " + pool.particles() + " частиц над головой"), true);
        } catch (RuntimeException e) {
            player.sendMessage(Text.literal("FoxPhys: " + e.getMessage()), true);
        }
        return TypedActionResult.success(stack, false);
    }
}
