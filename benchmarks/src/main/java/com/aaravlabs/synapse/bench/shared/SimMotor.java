package com.aaravlabs.synapse.bench.shared;

/**
 * Simulated {@code DcMotorEx} at the physical I/O boundary. A measured
 * actuation path pays the {@link LynxBus} transfer cost (when this motor was
 * given a bus), then a plain volatile field write into {@link SimPlant} plus
 * the actuation stamp the plan requires to live inside the framework-invoked
 * device write. The no-bus constructor exists for the mock-budget fixture,
 * which measures the bare mock write.
 */
public final class SimMotor {

    private final SimPlant plant;
    private final int channel;
    private final LatencyProbe probe;
    private final LynxBus bus;
    private volatile double lastPower;

    public SimMotor(SimPlant plant, int channel, LatencyProbe probe) {
        this(plant, channel, probe, null);
    }

    public SimMotor(SimPlant plant, int channel, LatencyProbe probe, LynxBus bus) {
        this.plant = plant;
        this.channel = channel;
        this.probe = probe;
        this.bus = bus;
    }

    /** Mirror of {@code DcMotorEx.setPower}. Runs on the framework-invoked thread. */
    public void setPower(double power) {
        if (bus != null) {
            bus.write();
        }
        lastPower = power;
        switch (channel) {
            case SimPlant.LEFT:
                plant.leftPower = power;
                break;
            case SimPlant.RIGHT:
                plant.rightPower = power;
                break;
            case SimPlant.LIFT:
                plant.liftPower = power;
                break;
            case SimPlant.INTAKE:
                plant.intakePower = power;
                break;
            default:
                throw new IllegalStateException("bad channel " + channel);
        }
        if (probe != null) {
            probe.actuation(System.nanoTime(), power);
        }
    }

    public double getPower() {
        return lastPower;
    }
}
