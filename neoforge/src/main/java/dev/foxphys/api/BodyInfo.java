package dev.foxphys.api;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A read-only copy of one rigid body at the last completed step. Positions are world coordinates.
 *
 * @param id           body id, stable while the body exists
 * @param centre       centre of mass, world coordinates
 * @param rotation     unit quaternion (x, y, z, w); a fresh array on every call
 * @param mass         kg
 * @param velocity     m/s
 * @param angularVelocity rad/s
 * @param bounds       axis-aligned box that contains the body, world coordinates; null when unknown
 * @param partCount    number of collision parts (boxes) of the body
 * @param assembled    true when the body was made from blocks, so {@link PhysicsWorld#disassemble} can return them
 */
public record BodyInfo(long id, Vec3 centre, double[] rotation, double mass, Vec3 velocity,
                       Vec3 angularVelocity, AABB bounds, int partCount, boolean assembled) {

    public BodyInfo {
        rotation = rotation.clone();
    }

    @Override
    public double[] rotation() {
        return rotation.clone();
    }
}
