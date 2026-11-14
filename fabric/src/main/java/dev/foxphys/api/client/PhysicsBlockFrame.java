package dev.foxphys.api.client;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/** Interpolated client pose for one visible block cell belonging to a rigid body. */
public record PhysicsBlockFrame(BlockPos cell, long bodyId, BlockState originalState,
                                Vec3d center, Quaternionf rotation) {
    public PhysicsBlockFrame {
        cell = cell.toImmutable();
        rotation = new Quaternionf(rotation);
    }

    @Override public Quaternionf rotation() { return new Quaternionf(rotation); }
}
