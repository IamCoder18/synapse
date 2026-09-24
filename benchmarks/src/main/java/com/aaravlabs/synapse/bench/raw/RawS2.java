package com.aaravlabs.synapse.bench.raw;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * S2 in idiomatic raw FTC: drive + intake + lift PIDF + outtake on two gamepads,
 * mixed target rates, one 0.5 ms auto-align computation on a gamepad event —
 * everything serialized in the single {@code loop()}.
 */
public final class RawS2 extends OpMode implements PairRunner {

    private static final long DRIVE_PERIOD_NANOS = 20_000_000L;
    private static final long LIFT_PERIOD_NANOS = 10_000_000L;
    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter loopMeter;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter telemetryMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();

    private boolean prevBumper1;
    private boolean prevA;
    private boolean prevBumper2;
    private boolean intakeRunning;
    private boolean outtaking;
    private double alignOffset;
    private long alignSeq;
    private long lastDrive;
    private long lastLift;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public RawS2(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
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
        }, "raw-s2");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        active = false;
        if (thread != null) {
            try {
                thread.join(1000);
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
            double y = gamepad1.left_stick_y;
            double turn = gamepad1.left_stick_x;
            double rightY = gamepad1.right_stick_y;
            world.leftMotor().setPower(y + turn + alignOffset);
            world.rightMotor().setPower(rightY - turn - alignOffset);
        }

        if (now - lastLift >= LIFT_PERIOD_NANOS) {
            lastLift = now;
            liftMeter.tick(now);
            double target = world.setpoints().liftTargetAt(now);
            double power = liftPidf.update(world.plant().liftPos, target, now);
            world.liftMotor().setPower(power);
        }

        boolean bumper1 = gamepad1.right_bumper;
        if (bumper1 && !prevBumper1) {
            intakeRunning = !intakeRunning;
            outtaking = false;
            world.intakeMotor().setPower(intakeRunning ? 1.0 : 0.0);
        }
        prevBumper1 = bumper1;

        boolean bumper2 = gamepad2.left_bumper;
        if (bumper2) {
            outtaking = true;
            intakeRunning = false;
            world.intakeMotor().setPower(-1.0);
        } else if (bumper2 != prevBumper2 && !intakeRunning) {
            world.intakeMotor().setPower(0.0);
        }
        prevBumper2 = bumper2;

        boolean a = gamepad1.a;
        if (a && !prevA) {
            alignOffset = BusyWork.autoAlign(alignSeq++) * 0.02;
        }
        prevA = a;

        if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
            lastTelemetry = now;
            telemetryMeter.tick(now);
            telemetry.addData("lift", world.plant().liftPos);
            telemetry.addData("align", alignOffset);
            telemetry.update();
        }
    }
}
