package com.aaravlabs.synapse.bench.shared;

/**
 * Shared physics "world": a differential drive base, a lift with gravity/friction,
 * and an intake roller, integrated at 1 kHz on its own thread. Device writes into
 * this plant are plain volatile field stores (constant cost, allocation-free).
 * The integrator also scores tracking error against {@link Setpoints}, which is
 * world-side bookkeeping and never part of a measured dispatch segment.
 */
public final class SimPlant implements AutoCloseable {

    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int LIFT = 2;
    public static final int INTAKE = 3;

    private static final double DT = 0.001;
    private static final double WHEEL_VMAX = 1.4;
    private static final double WHEEL_TAU = 0.045;
    private static final double LIFT_ACC_GAIN = 2500.0;
    private static final double LIFT_GRAVITY = 200.0;
    private static final double LIFT_DAMP = 1.0;
    private static final double LIFT_MAX = 1150.0;
    private static final double ROLLER_TAU = 0.08;
    /** Halved because the drive law splits ±corr across two motors (differential = 2·corr). */
    private static final double HEADING_ACC_GAIN = 17.5;
    private static final double HEADING_DAMP = 4.0;

    // ---- device inputs (written by framework-invoked code) ----------------
    public volatile double leftPower;
    public volatile double rightPower;
    public volatile double liftPower;
    public volatile double intakePower;
    public volatile double servoPos;

    /**
     * Yaw nudge commanded by auto-align/vision (radians added to the heading
     * setpoint). Written exactly like a device command and scored by this plant,
     * so every style tracks the same reference.
     */
    public volatile double headingBias;

    // ---- integrator state (written by the physics thread) -----------------
    /** Sim clock in nanos; the sensor layer keys its noise/dropout process off this. */
    public volatile long simNanos;
    public volatile double leftWheelVel;
    public volatile double rightWheelVel;
    public volatile double x;
    public volatile double y;
    public volatile double heading;
    public volatile double headingVel;
    public volatile double liftPos = Setpoints.LIFT_LOW;
    public volatile double liftVel;
    public volatile double rollerVel;

    private final Setpoints setpoints;
    private final boolean trackLift;
    private final boolean trackHeading;

    private double liftSse;
    private double headingSse;
    private long trackSamples;
    private volatile boolean scoring = true;

    private volatile boolean running;
    private Thread thread;

    public SimPlant(Setpoints setpoints, boolean trackLift, boolean trackHeading) {
        this.setpoints = setpoints;
        this.trackLift = trackLift;
        this.trackHeading = trackHeading;
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "sim-plant");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long next = System.nanoTime();
        long simNanos = 0L;
        while (running) {
            step(simNanos);
            simNanos += 1_000_000L;
            next += 1_000_000L;
            long now;
            while ((now = System.nanoTime()) - next < 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(next - now, 50_000L));
            }
        }
    }

    private void step(long simNanos) {
        this.simNanos = simNanos;
        double lp = clampPower(leftPower);
        double rp = clampPower(rightPower);
        double ip = clampPower(intakePower);
        double fp = clampPower(liftPower);

        leftWheelVel += (lp * WHEEL_VMAX - leftWheelVel) * (DT / WHEEL_TAU);
        rightWheelVel += (rp * WHEEL_VMAX - rightWheelVel) * (DT / WHEEL_TAU);

        double v = 0.5 * (leftWheelVel + rightWheelVel);
        x += v * Math.cos(heading) * DT;
        y += v * Math.sin(heading) * DT;

        // Right wheel faster = yaw left (counter-clockwise, heading increases).
        headingVel += ((rp - lp) * HEADING_ACC_GAIN - HEADING_DAMP * headingVel
                + headingDisturbance(simNanos)) * DT;
        heading += headingVel * DT;

        double liftAcc = fp * LIFT_ACC_GAIN - LIFT_GRAVITY - LIFT_DAMP * liftVel
                + liftDisturbance(simNanos);
        liftVel += liftAcc * DT;
        liftPos += liftVel * DT;
        if (liftPos < 0) {
            liftPos = 0;
            liftVel = 0;
        } else if (liftPos > LIFT_MAX) {
            liftPos = LIFT_MAX;
            liftVel = 0;
        }

        rollerVel += (ip * 12.0 - rollerVel) * (DT / ROLLER_TAU);

        scoreTracking(simNanos);
    }

    /**
     * Deterministic broadband load disturbances (sum of sines, identical for every
     * style). Frequency content sits at 7-55 Hz: a control loop stalled behind a
     * slow consumer (≈50 Hz or worse) cannot reject the upper bands, while a
     * 100-200 Hz loop can. See benchmarks/README.md for the offline calibration.
     */
    private static double liftDisturbance(long simNanos) {
        double t = simNanos * 1e-9;
        return 5000.0 * Math.sin(2 * Math.PI * 7.0 * t)
                + 15000.0 * Math.sin(2 * Math.PI * 16.0 * t)
                + 15000.0 * Math.sin(2 * Math.PI * 33.0 * t)
                + 12000.0 * Math.sin(2 * Math.PI * 55.0 * t);
    }

    /** Deterministic yaw torque noise at 9/20/40 Hz. */
    private static double headingDisturbance(long simNanos) {
        double t = simNanos * 1e-9;
        return 4.0 * Math.sin(2 * Math.PI * 9.0 * t)
                + 4.0 * Math.sin(2 * Math.PI * 20.0 * t)
                + 4.0 * Math.sin(2 * Math.PI * 40.0 * t);
    }

    private void scoreTracking(long simNanos) {
        if (!scoring) return;
        if (!trackLift && !trackHeading) return;
        // Score against the same wall-clock setpoint the style code tracks; a
        // sim-clock reference would drift from the controllers' System.nanoTime()
        // and invent tracking error.
        long now = System.nanoTime();
        synchronized (this) {
            if (trackLift) {
                double err = liftPos - setpoints.liftTargetAt(now);
                liftSse += err * err;
            }
            if (trackHeading) {
                double err = heading - (setpoints.headingTargetAt(now) + headingBias);
                headingSse += err * err;
            }
            trackSamples++;
        }
    }

    /** Stop accumulating tracking error (called at the end of the measurement window). */
    public void freezeTracking() {
        scoring = false;
    }

    /** Zero the tracking accumulators at the start of a measurement window. */
    public void resetTracking() {
        synchronized (this) {
            liftSse = 0;
            headingSse = 0;
            trackSamples = 0;
        }
        scoring = true;
    }

    public double liftRmse() {
        synchronized (this) {
            if (!trackLift || trackSamples == 0) return 0;
            return Math.sqrt(liftSse / trackSamples);
        }
    }

    public double headingRmse() {
        synchronized (this) {
            if (!trackHeading || trackSamples == 0) return 0;
            return Math.sqrt(headingSse / trackSamples);
        }
    }

    private static double clampPower(double p) {
        if (p > 1.0) return 1.0;
        if (p < -1.0) return -1.0;
        return Math.abs(p) < 0.03 ? 0.0 : p;
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
