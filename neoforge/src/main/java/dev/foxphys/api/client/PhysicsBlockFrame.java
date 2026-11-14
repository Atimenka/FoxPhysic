package dev.foxphys.api.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Interpolated client pose for one visible block cell belonging to a rigid body. */
public record PhysicsBlockFrame(BlockPos cell, long bodyId, BlockState originalState,
                                Vec3 center, Quaternionf rotation) {
    public PhysicsBlockFrame {
        cell = new BlockPos(cell.getX(), cell.getY(), cell.getZ());
        rotation = new Quaternionf(rotation);
    }

    @Override public Quaternionf rotation() { return new Quaternionf(rotation); }
}
