package com.aaravlabs.synapse.bench.raw;

import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * S1 in idiomatic raw FTC: the common rookie TeleOp. Tank drive, one servo,
 * intake toggle on bumper edges, telemetry at 10 Hz — all in one {@code loop()}.
 */
public final class RawS1 extends OpMode implements PairRunner {

    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter loopMeter;
    private final TaskMeter telemetryMeter;

    private boolean prevBumper;
    private boolean prevX;
    private boolean intakeRunning;
    private boolean servoOpen;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public RawS1(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
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
        }, "raw-s1");
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

        world.leftMotor().setPower(gamepad1.left_stick_y);
        world.rightMotor().setPower(gamepad1.right_stick_y);

        boolean bumper = gamepad1.right_bumper;
        if (bumper && !prevBumper) {
            intakeRunning = true;
            world.intakeMotor().setPower(1.0);
        } else if (!bumper && prevBumper) {
            intakeRunning = false;
            world.intakeMotor().setPower(0.0);
        }
        prevBumper = bumper;

        boolean x = gamepad1.x;
        if (x && !prevX) {
            servoOpen = !servoOpen;
            world.servo().setPosition(servoOpen ? 1.0 : 0.0);
        }
        prevX = x;

        long now = System.nanoTime();
        if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
            lastTelemetry = now;
            telemetryMeter.tick(now);
            telemetry.addData("intake", intakeRunning ? "on" : "off");
            telemetry.addData("servo", servoOpen ? "open" : "closed");
            telemetry.update();
        }
    }
}
