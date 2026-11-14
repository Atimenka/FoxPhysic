package dev.foxphys.api;

/**
 * Counters of one physics world at the last completed step.
 *
 * @param simSeconds      simulated time in seconds
 * @param fixedSteps      number of fixed steps taken
 * @param bodyCount       rigid bodies in the native world
 * @param assemblyCount   bodies that were made from blocks (they hold blocks that can be returned)
 * @param staticColliders static terrain boxes the world knows about
 * @param lastStepMicros  wall time of the last native step, in microseconds
 * @param paused          true while the simulation is frozen
 */
public record WorldStats(double simSeconds, long fixedSteps, int bodyCount, int assemblyCount,
                         int staticColliders, long lastStepMicros, boolean paused) {}
