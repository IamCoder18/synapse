package com.aaravlabs.synapse.bench.raw;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.Setpoints;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.SimCamera;
import com.aaravlabs.synapse.bench.shared.SyntheticVisionPipeline;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * S3 in idiomatic raw FTC: everything from S2 plus 30 Hz vision (~3 ms/frame),
 * two PIDF loops (lift 100 Hz, heading hold 200 Hz) and a slow debug logger
 * (15 ms per state update) — all serialized in the single {@code loop()}.
 * This is the case raw FTC is expected to lose badly.
 */
public final class RawS3 extends OpMode implements PairRunner {

    private static final long DRIVE_PERIOD_NANOS = 20_000_000L;
    private static final long LIFT_PERIOD_NANOS = 10_000_000L;
    private static final long HEADING_PERIOD_NANOS = 5_000_000L;
    private static final long STATE_PERIOD_NANOS = 20_000_000L;
    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter loopMeter;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter headingMeter;
    private final TaskMeter visionMeter;
    private final TaskMeter loggerMeter;
    private final TaskMeter telemetryMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();
    private final SharedPidf headingPidf = SharedPidf.forHeading();

    private boolean prevBumper1;
    private boolean prevA;
    private boolean prevBumper2;
    private boolean intakeRunning;
    private double stickY;
    private double stickRightY;
    private long alignSeq;
    private long stateSeq;
    private long lastDrive;
    private long lastLift;
    private long lastHeading;
    private long lastState;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public RawS3(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
        this.headingMeter = metrics.task("headingPidf", world.scenario().headingTargetHz());
        this.visionMeter = metrics.task("vision", SimCamera.HZ);
        this.loggerMeter = metrics.task("logger", world.scenario().stateTargetHz());
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
    }

    @Override
    public void start() {
        active = true;
        gamepad1 = world.gamepad1();
        gamepad2 = world.gamepad2();
        telemetry = world.telemetry();
        thread = new Thread(() -> {
            init();
            while (active) {
                loop();
            }
        }, "raw-s3");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        active = false;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void init() {
    }

    @Override
    public void loop() {
        loopMeter.tick();
        metrics.countLoopIteration();
        long now = System.nanoTime();

        if (now - lastDrive >= DRIVE_PERIOD_NANOS) {
            lastDrive = now;
            driveMeter.tick(now);
            stickY = gamepad1.left_stick_y;
            stickRightY = gamepad1.right_stick_y;
        }

        if (now - lastHeading >= HEADING_PERIOD_NANOS) {
            lastHeading = now;
            headingMeter.tick(now);
            double target = world.setpoints().headingTargetAt(now) + world.plant().headingBias;
            double corr = headingPidf.update(world.sensors().heading(), target, now);
            world.leftMotor().setPower(stickY - corr);
            world.rightMotor().setPower(stickRightY + corr);
        }

        if (now - lastLift >= LIFT_PERIOD_NANOS) {
            lastLift = now;
            liftMeter.tick(now);
            double target = world.setpoints().liftTargetAt(now);
            double power = liftPidf.update(world.sensors().liftPos(), target, now);
            world.liftMotor().setPower(power);
        }

        SimCamera camera = world.camera();
        SimCamera.Frame frame = camera.pollFrame();
        if (frame != null) {
            visionMeter.tick(now);
            world.plant().headingBias = SyntheticVisionPipeline.process(frame.buf, frame.seq) * 0.002;
        }

        boolean bumper1 = gamepad1.right_bumper;
        if (bumper1 && !prevBumper1) {
            intakeRunning = !intakeRunning;
            world.intakeMotor().setPower(intakeRunning ? 1.0 : 0.0);
        }
        prevBumper1 = bumper1;

        boolean bumper2 = gamepad2.left_bumper;
        if (bumper2) {
            intakeRunning = false;
            world.intakeMotor().setPower(-1.0);
        } else if (prevBumper2 && !intakeRunning) {
            world.intakeMotor().setPower(0.0);
        }
        prevBumper2 = bumper2;

        boolean a = gamepad1.a;
        if (a && !prevA) {
            world.plant().headingBias = BusyWork.autoAlign(alignSeq++) * 0.002;
        }
        prevA = a;

        if (now - lastState >= STATE_PERIOD_NANOS) {
            lastState = now;
            stateSeq++;
            loggerMeter.tick(now);
            // Slow debug logger consuming the state update — in a single-loop
            // robot this runs inline and stalls everything else.
            BusyWork.slowLog(stateSeq);
        }

        if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
            lastTelemetry = now;
            telemetryMeter.tick(now);
            telemetry.addData("lift", world.sensors().liftPos());
            telemetry.addData("heading", world.sensors().heading());
            telemetry.update();
        }
    }
}
