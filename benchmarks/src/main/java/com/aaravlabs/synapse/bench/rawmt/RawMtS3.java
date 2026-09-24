package com.aaravlabs.synapse.bench.rawmt;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.SimCamera;
import com.aaravlabs.synapse.bench.shared.SyntheticVisionPipeline;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S3 honesty variant: raw FTC with hand-rolled threads — vision on its own
 * thread, the slow debug logger on its own thread. The control loop keeps its
 * rate, which is exactly the isolation Synapse gets from its callback pool.
 */
public final class RawMtS3 extends OpMode implements PairRunner {

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
    private final TaskMeter alignMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();
    private final SharedPidf headingPidf = SharedPidf.forHeading();

    private final ConcurrentLinkedQueue<Long> logQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong alignRequests = new AtomicLong();
    private final AtomicLong alignCompleted = new AtomicLong();

    private boolean prevBumper1;
    private boolean prevA;
    private boolean prevBumper2;
    private boolean intakeRunning;
    private volatile double stickY;
    private volatile double stickRightY;
    private long stateSeq;
    private long lastDrive;
    private long lastLift;
    private long lastHeading;
    private long lastState;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread loopThread;
    private Thread visionThread;
    private Thread loggerThread;
    private Thread alignThread;

    public RawMtS3(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
        this.headingMeter = metrics.task("headingPidf", world.scenario().headingTargetHz());
        this.visionMeter = metrics.task("vision", SimCamera.HZ);
        this.loggerMeter = metrics.task("logger", world.scenario().stateTargetHz());
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
        this.alignMeter = metrics.task("align", 0);
    }

    @Override
    public void start() {
        active = true;
        gamepad1 = world.gamepad1();
        gamepad2 = world.gamepad2();
        telemetry = world.telemetry();

        visionThread = new Thread(this::visionLoop, "rawmt-vision");
        visionThread.setDaemon(true);
        visionThread.start();

        loggerThread = new Thread(this::loggerLoop, "rawmt-logger");
        loggerThread.setDaemon(true);
        loggerThread.start();

        alignThread = new Thread(this::alignLoop, "rawmt-align");
        alignThread.setDaemon(true);
        alignThread.start();

        loopThread = new Thread(() -> {
            init();
            while (active) {
                loop();
            }
        }, "rawmt-s3");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    private void visionLoop() {
        while (active) {
            SimCamera.Frame frame = world.camera().pollFrame();
            if (frame == null) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            visionMeter.tick();
            world.plant().headingBias = SyntheticVisionPipeline.process(frame.buf, frame.seq) * 0.002;
        }
    }

    private void loggerLoop() {
        while (active || !logQueue.isEmpty()) {
            Long seq = logQueue.poll();
            if (seq == null) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            loggerMeter.tick();
            BusyWork.slowLog(seq);
        }
    }

    private void alignLoop() {
        while (active) {
            long req = alignRequests.get();
            if (req != alignCompleted.get()) {
                alignMeter.tick();
                world.plant().headingBias = BusyWork.autoAlign(req) * 0.002;
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
        joinQuietly(loopThread, 2000);
        joinQuietly(visionThread, 2000);
        joinQuietly(loggerThread, 2000);
        joinQuietly(alignThread, 2000);
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
            stickY = gamepad1.left_stick_y;
            stickRightY = gamepad1.right_stick_y;
        }

        if (now - lastHeading >= HEADING_PERIOD_NANOS) {
            lastHeading = now;
            headingMeter.tick(now);
            double target = world.setpoints().headingTargetAt(now) + world.plant().headingBias;
            double corr = headingPidf.update(world.plant().heading, target, now);
            world.leftMotor().setPower(stickY - corr);
            world.rightMotor().setPower(stickRightY + corr);
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
            alignRequests.incrementAndGet();
        }
        prevA = a;

        if (now - lastState >= STATE_PERIOD_NANOS) {
            lastState = now;
            stateSeq++;
            logQueue.add(stateSeq);
        }

        if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
            lastTelemetry = now;
            telemetryMeter.tick(now);
            telemetry.addData("lift", world.plant().liftPos);
            telemetry.addData("heading", world.plant().heading);
            telemetry.update();
        }
    }
}
