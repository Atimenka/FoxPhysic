package dev.foxphys;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Wind for the rigid bodies and the soft bodies, from the weather and the game clock.
 *
 * Base speed follows the weather: calm about 1.5 m/s, rain about 5.5 m/s (rain level 1),
 * thunderstorm about 13.5 m/s (thunder level 1). The direction drifts slowly, and gusts are
 * sums of sines of the game time, stronger in a storm. Everything is a pure function of the
 * game time and the weather levels, so the same tick always gives the same wind.
 */
public final class Wind {
    private Wind() {}

    /** Calm-air speed, m/s. */
    static final double CALM = 1.5;
    /** Extra speed at full rain, m/s. */
    static final double RAIN = 4.0;
    /** Extra speed at full thunder, m/s. */
    static final double THUNDER = 8.0;

    /** Wind velocity (m/s, world axes) at the level's current game time and weather. */
    public static Vec3 at(ServerLevel level) {
        double t = level.getGameTime() / 20.0;
        double rain = clamp01(level.getRainLevel(1f));
        double thunder = clamp01(level.getThunderLevel(1f));
        double base = CALM + RAIN * rain + THUNDER * thunder;
        double direction = 0.6 + 0.35 * Math.sin(t / 120.0);
        double gustiness = 0.25 + 0.35 * thunder;
        double gust = 1.0 + gustiness * Math.sin(t / 7.3)
                + 0.2 * Math.sin(t / 2.9 + 1.3) + 0.1 * Math.sin(t / 0.9);
        double speed = Math.max(0.0, base * gust);
        double vertical = 0.6 * thunder * Math.sin(t / 1.7);
        return new Vec3(Math.cos(direction) * speed, vertical, Math.sin(direction) * speed);
    }

    private static double clamp01(float v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
