package com.aaravlabs.synapse.bench.shared;

/**
 * Simulated {@code Servo} at the physical I/O boundary. Position writes are plain
 * volatile stores plus the actuation stamp inside the framework-invoked call.
 */
public final class SimServo {

    private final SimPlant plant;
    private final LatencyProbe probe;
    private volatile double position;

    public SimServo(SimPlant plant, LatencyProbe probe) {
        this.plant = plant;
        this.probe = probe;
    }

    /** Mirror of {@code Servo.setPosition}. */
    public void setPosition(double pos) {
        position = pos;
        plant.servoPos = pos;
        if (probe != null) {
            probe.actuation(System.nanoTime(), pos);
        }
    }

    public double getPosition() {
        return position;
    }
}
