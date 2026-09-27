package com.aaravlabs.synapse.bench.shared;

/**
 * Control-Hub-class sensor reads at the I/O boundary. Every read pays a Lynx
 * bus transaction (a bulk read of both control registers pays one); values are
 * quantized to encoder/IMU resolution, carry smooth (correlated) noise, and
 * occasionally drop out — a dropped read returns the last good sample like a
 * real retry-after-bulk-failure would. All randomness is a pure function of
 * the world seed and the sim clock, so every style sees the identical sensor
 * process. World-side physics stays exact in {@link SimPlant}; this class is
 * only what the robot code can observe.
 */
public final class SimSensors {

    private static final double LIFT_TICK = 1.0;
    private static final double HEADING_TICK = 0.0003;
    private static final long DROPOUT_BUCKET_NANOS = 20_000_000L;

    private final SimPlant plant;
    private final LynxBus bus;
    private final long seed;
    private final double n1;
    private final double n2;
    private final double n3;

    private volatile double lastLift;
    private volatile double lastHeading;
    private volatile boolean haveLift;
    private volatile boolean haveHeading;

    public SimSensors(SimPlant plant, LynxBus bus, long seed) {
        this.plant = plant;
        this.bus = bus;
        this.seed = seed;
        this.n1 = 0.8 + (seed % 7) * 0.11;
        this.n2 = 1.9 + (seed % 5) * 0.13;
        this.n3 = 4.3 + (seed % 3) * 0.17;
    }

    /** Single-register lift encoder read (one bus transaction). */
    public double liftPos() {
        bus.read();
        double t = plant.simNanos * 1e-9;
        double v = quantize(plant.liftPos + liftNoise(t), LIFT_TICK);
        if (dropped()) return haveLift ? lastLift : v;
        lastLift = v;
        haveLift = true;
        return v;
    }

    /** Single-register IMU heading read (one bus transaction). */
    public double heading() {
        bus.read();
        double t = plant.simNanos * 1e-9;
        double v = quantize(plant.heading + headingNoise(t), HEADING_TICK);
        if (dropped()) return haveHeading ? lastHeading : v;
        lastHeading = v;
        haveHeading = true;
        return v;
    }

    /** Bulk read of both control registers in one bus transaction. */
    public double[] readControl() {
        bus.bulkRead(2);
        double t = plant.simNanos * 1e-9;
        double lift = quantize(plant.liftPos + liftNoise(t), LIFT_TICK);
        double head = quantize(plant.heading + headingNoise(t), HEADING_TICK);
        if (dropped()) {
            return new double[] {
                    haveLift ? lastLift : lift,
                    haveHeading ? lastHeading : head
            };
        }
        lastLift = lift;
        lastHeading = head;
        haveLift = true;
        haveHeading = true;
        return new double[] {lift, head};
    }

    private boolean dropped() {
        long bucket = plant.simNanos / DROPOUT_BUCKET_NANOS;
        long h = seed * 0x9E3779B97F4A7C15L + bucket * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return (h & 63L) == 0;
    }

    private double liftNoise(double t) {
        return 0.7 * Math.sin(n1 * t + seed) + 0.4 * Math.sin(n2 * t + seed * 0.5);
    }

    private double headingNoise(double t) {
        return 0.0025 * Math.sin(n2 * t + seed * 0.3) + 0.0015 * Math.sin(n3 * t + seed);
    }

    private static double quantize(double v, double tick) {
        return Math.rint(v / tick) * tick;
    }
}
