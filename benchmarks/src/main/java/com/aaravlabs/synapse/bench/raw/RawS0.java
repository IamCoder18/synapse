package com.aaravlabs.synapse.bench.raw;

import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * S0 in idiomatic raw FTC: one stick, one motor, one {@code loop()}. No
 * subscriptions, no commands — the floor case where raw FTC is expected to win.
 */
public final class RawS0 extends OpMode implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter loopMeter;

    private volatile boolean active;
    private Thread thread;

    public RawS0(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.loopMeter = metrics.task("loop", 0);
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
        }, "raw-s0");
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
    }
}
