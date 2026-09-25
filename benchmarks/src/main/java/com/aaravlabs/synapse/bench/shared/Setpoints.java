package com.aaravlabs.synapse.bench.shared;

/**
 * Deterministic setpoint trajectories shared by every style. Controllers read these
 * and {@link SimPlant} scores tracking error against the very same functions, so the
 * control-quality metric cannot be gamed by any style.
 */
public final class Setpoints {

    /** Lift sweep between low and high ticks. */
    public static final double LIFT_LOW = 350.0;
    public static final double LIFT_HIGH = 650.0;
    private static final long LIFT_HALF_PERIOD_NANOS = 1_500_000_000L;

    private final long originNanos;

    public Setpoints(long originNanos) {
        this.originNanos = originNanos;
    }

    public long originNanos() {
        return originNanos;
    }

    /** Triangle sweep of the lift; identical phase for every style. */
    public double liftTargetAt(long nowNanos) {
        long phase = Math.floorMod(nowNanos - originNanos, 2 * LIFT_HALF_PERIOD_NANOS);
        double frac;
        if (phase < LIFT_HALF_PERIOD_NANOS) {
            frac = phase / (double) LIFT_HALF_PERIOD_NANOS;
        } else {
            frac = 1.0 - (phase - LIFT_HALF_PERIOD_NANOS) / (double) LIFT_HALF_PERIOD_NANOS;
        }
        return LIFT_LOW + (LIFT_HIGH - LIFT_LOW) * frac;
    }

    /** Heading-hold target: keep yaw at 0 while disturbances hit the plant. */
    public double headingTargetAt(long nowNanos) {
        return 0.0;
    }
}
