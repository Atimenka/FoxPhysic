package dev.foxphys.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
    @Invoker("setBlockState") void foxphys$blockState(BlockState value);
}
