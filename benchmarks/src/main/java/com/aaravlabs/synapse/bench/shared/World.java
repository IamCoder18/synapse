package com.aaravlabs.synapse.bench.shared;

import com.qualcomm.robotcore.hardware.Gamepad;

/**
 * Per-run shared world: real stub {@link Gamepad}s, {@link SimPlant} devices on
 * a shared {@link LynxBus}, realistic {@link SimSensors} reads, a
 * {@link DriverStation} packet link for gamepad updates, {@link SimTelemetry},
 * setpoints and the seeded stimulus timeline. Style packages consume this and
 * their framework APIs only.
 */
public final class World implements AutoCloseable {

    private final Scenario scenario;
    private final long seed;
    private final Metrics metrics;

    private final Gamepad gamepad1 = new Gamepad();
    private final Gamepad gamepad2 = new Gamepad();
    private final Setpoints setpoints;
    private final SimPlant plant;
    private final LynxBus bus;
    private final SimSensors sensors;
    private final SimMotor leftMotor;
    private final SimMotor rightMotor;
    private final SimMotor liftMotor;
    private final SimMotor intakeMotor;
    private final SimServo servo;
    private final SimCamera camera;
    private final SimTelemetry telemetry = new SimTelemetry();
    private final DriverStation driverStation;
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
        this.bus = new LynxBus();
        this.sensors = new SimSensors(plant, bus, seed);
        this.driverStation = new DriverStation(seed);
        // Only the drive-left write carries the actuation latency probe: the measured
        // path is the stick stimulus -> drive actuation. Other device writes stay
        // un-instrumented so they cannot steal the pairing.
        this.leftMotor = new SimMotor(plant, SimPlant.LEFT, actuation, bus);
        this.rightMotor = new SimMotor(plant, SimPlant.RIGHT, null, bus);
        this.liftMotor = new SimMotor(plant, SimPlant.LIFT, null, bus);
        this.intakeMotor = new SimMotor(plant, SimPlant.INTAKE, null, bus);
        this.servo = new SimServo(plant, null, bus);
        this.camera = scenario.hasVision() ? new SimCamera() : null;
        this.stimulus = StimulusTimeline.build(scenario, seed, gamepad1, gamepad2, actuation,
                driverStation, spanSec, leftPowerSign);
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

    public SimSensors sensors() {
        return sensors;
    }

    public LynxBus bus() {
        return bus;
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

    public SimTelemetry telemetry() {
        return telemetry;
    }

    public DriverStation driverStation() {
        return driverStation;
    }

    public StimulusTimeline stimulus() {
        return stimulus;
    }

    public LatencyProbe actuationProbe() {
        return actuation;
    }

    /** Start plant, camera capture, DS link and stimulus. */
    public void start() {
        plant.start();
        if (camera != null) camera.start();
        driverStation.start();
        stimulus.start();
    }

    @Override
    public void close() {
        stimulus.close();
        driverStation.close();
        if (camera != null) camera.close();
        plant.close();
    }
}
