package dev.foxphys.api;

import net.minecraft.world.phys.Vec3;

/**
 * A read-only copy of one soft body at the last step.
 *
 * @param id              soft body id
 * @param preset          rubber, jelly, cloth or rope
 * @param particles       number of particles
 * @param centre          mean particle position, world coordinates
 * @param maxSpeed        largest particle speed, m/s
 * @param kineticEnergy   J
 * @param volumeRatioMin  smallest volume of any tetrahedron relative to its rest volume
 * @param volumeRatioMax  largest volume ratio
 * @param stiffness       multiplier on the preset stiffness (1.0 = preset)
 * @param damping         damping in 1/s
 * @param finite          false if any value became NaN or infinite (the body is then unusable)
 */
public record SoftBodyInfo(int id, String preset, int particles, Vec3 centre, double maxSpeed,
                           double kineticEnergy, double volumeRatioMin, double volumeRatioMax,
                           double stiffness, double damping, boolean finite) {}
