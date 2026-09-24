package com.aaravlabs.synapse.bench.shared;

/**
 * Identical PIDF math for every style — controller code is deliberately not what
 * the suite benchmarks. Output is clamped to [-1, 1] motor power.
 */
public final class SharedPidf {

    private final double kP;
    private final double kI;
    private final double kD;
    private final double kF;
    private final double iLimit;

    private double integ;
    private double prevError;
    private long prevNanos;

    public SharedPidf(double kP, double kI, double kD, double kF, double iLimit) {
        this.kP = kP;
        this.kI = kI;
        this.kD = kD;
        this.kF = kF;
        this.iLimit = iLimit;
    }

    /** Lift position controller (ticks -> power), feedforward cancels gravity. */
    public static SharedPidf forLift() {
        return new SharedPidf(0.05, 0.001, 0.0025, 0.08, 80.0);
    }

    /** Drivetrain heading-hold controller (radians -> differential power). */
    public static SharedPidf forHeading() {
        return new SharedPidf(3.0, 0.0, 0.15, 0.0, 0.0);
    }

    public double update(double measurement, double setpoint, long nowNanos) {
        double dt = 0.005;
        if (prevNanos != 0) {
            double real = (nowNanos - prevNanos) * 1e-9;
            if (real > 0.0001 && real < 0.1) dt = real;
        }
        prevNanos = nowNanos;

        double error = setpoint - measurement;
        integ += error * dt;
        if (integ > iLimit) integ = iLimit;
        else if (integ < -iLimit) integ = -iLimit;
        double d = (error - prevError) / dt;
        prevError = error;

        double out = kP * error + kI * integ + kD * d + kF;
        if (out > 1.0) out = 1.0;
        else if (out < -1.0) out = -1.0;
        return out;
    }

    public void reset() {
        integ = 0;
        prevError = 0;
        prevNanos = 0;
    }
}
