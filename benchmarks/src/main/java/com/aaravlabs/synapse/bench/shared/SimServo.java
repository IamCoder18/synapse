package com.aaravlabs.synapse.bench.shared;

/**
 * Simulated {@code Servo} at the physical I/O boundary. Position writes pay
 * the {@link LynxBus} transfer cost (when given a bus), then a plain volatile
 * store plus the actuation stamp inside the framework-invoked call.
 */
public final class SimServo {

    private final SimPlant plant;
    private final LatencyProbe probe;
    private final LynxBus bus;
    private volatile double position;

    public SimServo(SimPlant plant, LatencyProbe probe) {
        this(plant, probe, null);
    }

    public SimServo(SimPlant plant, LatencyProbe probe, LynxBus bus) {
        this.plant = plant;
        this.probe = probe;
        this.bus = bus;
    }

    /** Mirror of {@code Servo.setPosition}. */
    public void setPosition(double pos) {
        if (bus != null) {
            bus.write();
        }
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
