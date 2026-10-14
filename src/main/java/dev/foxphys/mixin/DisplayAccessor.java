package dev.foxphys.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Presentation access only. No physics runs in these mixins. */
@Mixin(DisplayEntity.class)
public interface DisplayAccessor {
    @Invoker("setTransformation") void foxphys$transformation(AffineTransformation value);
    @Invoker("setInterpolationDuration") void foxphys$interpolationDuration(int value);
    @Invoker("setStartInterpolation") void foxphys$startInterpolation(int value);
    @Invoker("setTeleportDuration") void foxphys$teleportDuration(int value);
    @Invoker("setViewRange") void foxphys$viewRange(float value);
    @Invoker("setDisplayWidth") void foxphys$displayWidth(float value);
    @Invoker("setDisplayHeight") void foxphys$displayHeight(float value);
}
