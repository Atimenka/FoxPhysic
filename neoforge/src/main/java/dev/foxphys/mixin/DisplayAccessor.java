package dev.foxphys.mixin;

import net.minecraft.world.entity.Display;
import com.mojang.math.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Presentation access only. No physics runs in these mixins. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation") void foxphys$transformation(Transformation value);
    @Invoker("setTransformationInterpolationDuration") void foxphys$interpolationDuration(int value);
    @Invoker("setTransformationInterpolationDelay") void foxphys$startInterpolation(int value);
    @Invoker("setPosRotInterpolationDuration") void foxphys$teleportDuration(int value);
    @Invoker("setViewRange") void foxphys$viewRange(float value);
    @Invoker("setWidth") void foxphys$displayWidth(float value);
    @Invoker("setHeight") void foxphys$displayHeight(float value);
}
