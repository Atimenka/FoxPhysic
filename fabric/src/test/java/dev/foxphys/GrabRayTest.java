package dev.foxphys;

import dev.foxphys.Protocol.BodyState;
import dev.foxphys.Protocol.Part;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gravity gun aim: a ray must hit a 1 m body from the side, top and bottom at 3.9 blocks. */
class GrabRayTest {
    private static BodyState cube() {
        Part part = new Part(new float[]{0, 0, 0}, new float[]{1, 1, 1}, 500, 0.6f, 0f, "minecraft:oak_planks");
        return new BodyState(1, new float[]{0, 0, 0}, new float[]{0, 0, 0, 1}, new float[3], new float[3], 500,
                List.of(part), new float[3], new float[3], new float[]{-0.5f, -0.5f, -0.5f}, new float[]{0.5f, 0.5f, 0.5f});
    }

    @Test
    void sideTopBottomHitAtThreeAndAHalfBlocks() {
        BodyState b = cube();
        // Eye 3.9 blocks from the centre: the surface is at 3.4 blocks.
        double side = Interactions.rayHit(b, new Vec3d(-3.9, 0, 0), new Vec3d(1, 0, 0), 16.0);
        double top = Interactions.rayHit(b, new Vec3d(0, 3.9, 0), new Vec3d(0, -1, 0), 16.0);
        double bottom = Interactions.rayHit(b, new Vec3d(0, -3.9, 0), new Vec3d(0, 1, 0), 16.0);
        assertEquals(3.4, side, 1e-4, "side");
        assertEquals(3.4, top, 1e-4, "top");
        assertEquals(3.4, bottom, 1e-4, "bottom");
    }

    @Test
    void diagonalSideHitWithinReach() {
        BodyState b = cube();
        Vec3d eye = new Vec3d(-3.0, 2.0, -2.5);
        Vec3d dir = new Vec3d(3.0, -2.0, 2.5).normalize(); // aims at the cube
        double t = Interactions.rayHit(b, eye, dir, 16.0);
        assertTrue(!Double.isNaN(t), "diagonal ray missed the cube");
        assertTrue(t > 0 && t < 16.0, "distance out of reach: " + t);
    }
}
