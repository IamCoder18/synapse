package com.aaravlabs.synapse.bench.shared;

/**
 * Simulated {@code DcMotorEx} at the physical I/O boundary. The only thing a
 * measured actuation path does here is a plain volatile field write into
 * {@link SimPlant} plus the actuation stamp the plan requires to live inside the
 * framework-invoked device write. Constant-cost, allocation-free.
 */
public final class SimMotor {

    private final SimPlant plant;
    private final int channel;
    private final LatencyProbe probe;
    private volatile double lastPower;

    public SimMotor(SimPlant plant, int channel, LatencyProbe probe) {
        this.plant = plant;
        this.channel = channel;
        this.probe = probe;
    }

    /** Mirror of {@code DcMotorEx.setPower}. Runs on the framework-invoked thread. */
    public void setPower(double power) {
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
