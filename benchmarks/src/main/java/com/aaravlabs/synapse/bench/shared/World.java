package com.aaravlabs.synapse.bench.shared;

import com.qualcomm.robotcore.hardware.Gamepad;

/**
 * Per-run shared world: real stub {@link Gamepad}s, {@link SimPlant} devices,
 * optional {@link SimCamera}, setpoints and the seeded stimulus timeline. Style
 * packages consume this and their framework APIs only.
 */
public final class World implements AutoCloseable {

    private final Scenario scenario;
    private final long seed;
    private final Metrics metrics;

    private final Gamepad gamepad1 = new Gamepad();
    private final Gamepad gamepad2 = new Gamepad();
    private final Setpoints setpoints;
    private final SimPlant plant;
    private final SimMotor leftMotor;
    private final SimMotor rightMotor;
    private final SimMotor liftMotor;
    private final SimMotor intakeMotor;
    private final SimServo servo;
    private final SimCamera camera;
    private final NoopTelemetry telemetry = new NoopTelemetry();
    private final StimulusTimeline stimulus;
    private final LatencyProbe actuation;

    public World(Scenario scenario, long seed, Metrics metrics) {
        this(scenario, seed, metrics, 8.0, -1.0);
    }

    /**
     * @param spanSec       total warmup-plus-measure duration the stimulus covers
     * @param leftPowerSign style sign convention mapping a forward-positive stick
     *                      command to the expected left-motor power direction
     */
    public World(Scenario scenario, long seed, Metrics metrics,
                 double spanSec, double leftPowerSign) {
        this.scenario = scenario;
        this.seed = seed;
        this.metrics = metrics;
        this.actuation = metrics.probe("actuation");
        this.setpoints = new Setpoints(System.nanoTime() + 50_000_000L);
        this.plant = new SimPlant(setpoints, scenario.hasLift(), scenario.hasHeadingHold());
        // Only the drive-left write carries the actuation latency probe: the measured
        // path is the stick stimulus -> drive actuation. Other device writes stay
        // un-instrumented so they cannot steal the pairing.
        this.leftMotor = new SimMotor(plant, SimPlant.LEFT, actuation);
        this.rightMotor = new SimMotor(plant, SimPlant.RIGHT, null);
        this.liftMotor = new SimMotor(plant, SimPlant.LIFT, null);
        this.intakeMotor = new SimMotor(plant, SimPlant.INTAKE, null);
        this.servo = new SimServo(plant, null);
        this.camera = scenario.hasVision() ? new SimCamera() : null;
        this.stimulus = StimulusTimeline.build(scenario, seed, gamepad1, gamepad2, actuation,
                spanSec, leftPowerSign);
    }

    public Scenario scenario() {
        return scenario;
    }

    public long seed() {
        return seed;
    }

    public Metrics metrics() {
        return metrics;
    }

    public Gamepad gamepad1() {
        return gamepad1;
    }

    public Gamepad gamepad2() {
        return gamepad2;
    }

    public Setpoints setpoints() {
        return setpoints;
    }

    public SimPlant plant() {
        return plant;
    }

    public SimMotor leftMotor() {
        return leftMotor;
    }

    public SimMotor rightMotor() {
        return rightMotor;
    }

    /** Only the left motor carries the actuation latency probe (S0 uses it alone). */
    public SimMotor liftMotor() {
        return liftMotor;
    }

    public SimMotor intakeMotor() {
        return intakeMotor;
    }

    public SimServo servo() {
        return servo;
    }

    public SimCamera camera() {
        return camera;
    }

    public NoopTelemetry telemetry() {
        return telemetry;
    }

    public StimulusTimeline stimulus() {
        return stimulus;
    }

    public LatencyProbe actuationProbe() {
        return actuation;
    }

    /** Start plant, camera capture and stimulus. */
    public void start() {
        plant.start();
        if (camera != null) camera.start();
        stimulus.start();
    }

    @Override
    public void close() {
        stimulus.close();
        if (camera != null) camera.close();
        plant.close();
    }
}
