package com.aaravlabs.synapse.bench.rawmt;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * S2 honesty variant: the best a competent team writes without a framework —
 * raw FTC plus hand-rolled worker threads. The 0.5 ms auto-align kernel runs on
 * its own thread so the control loop rate does not slip.
 */
public final class RawMtS2 extends OpMode implements PairRunner {

    private static final long DRIVE_PERIOD_NANOS = 20_000_000L;
    private static final long LIFT_PERIOD_NANOS = 10_000_000L;
    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter loopMeter;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter telemetryMeter;
    private final TaskMeter alignMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();

    private boolean prevBumper1;
    private boolean prevA;
    private boolean prevBumper2;
    private boolean intakeRunning;
    private volatile double alignOffset;
    private final AtomicLong alignRequests = new AtomicLong();
    private final AtomicLong alignCompleted = new AtomicLong();

    private long lastDrive;
    private long lastLift;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread loopThread;
    private Thread alignThread;

    public RawMtS2(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
        this.alignMeter = metrics.task("align", 0);
    }

    @Override
    public void start() {
        active = true;
        gamepad1 = world.gamepad1();
        gamepad2 = world.gamepad2();
        telemetry = world.telemetry();

        alignThread = new Thread(this::alignLoop, "rawmt-align");
        alignThread.setDaemon(true);
        alignThread.start();

        loopThread = new Thread(() -> {
            init();
            while (active) {
                loop();
            }
        }, "rawmt-s2");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    private void alignLoop() {
        while (active) {
            long req = alignRequests.get();
            if (req != alignCompleted.get()) {
                alignMeter.tick();
                double offset = BusyWork.autoAlign(req) * 0.02;
                alignOffset = offset;
                alignCompleted.set(req);
            } else {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void stop() {
        active = false;
        joinQuietly(loopThread, 1000);
        joinQuietly(alignThread, 1000);
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            double align = alignOffset;
            world.leftMotor().setPower(y + turn + align);
            world.rightMotor().setPower(rightY - turn - align);
        }

        if (now - lastLift >= LIFT_PERIOD_NANOS) {
            lastLift = now;
            liftMeter.tick(now);
            double target = world.setpoints().liftTargetAt(now);
            double power = liftPidf.update(world.sensors().liftPos(), target, now);
            world.liftMotor().setPower(power);
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
        } else if (bumper2 != prevBumper2 && !intakeRunning) {
            world.intakeMotor().setPower(0.0);
        }
        prevBumper2 = bumper2;

        boolean a = gamepad1.a;
        if (a && !prevA) {
            alignRequests.incrementAndGet();
        }
        prevA = a;

        if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
            lastTelemetry = now;
            telemetryMeter.tick(now);
            telemetry.addData("lift", world.sensors().liftPos());
            telemetry.addData("align", alignOffset);
            telemetry.update();
        }
    }
}
