package com.aaravlabs.synapse.bench.synapse;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;

/**
 * S0 in idiomatic Synapse: one stick → one motor. {@link GamepadAdaptor} publishes
 * the real stub {@code Gamepad} fields onto the bus; a {@code @SubscribedTo}
 * handler hops to the real hardware thread through {@code hardware().run} and
 * writes the device. Minimum nodes, real dispatch end to end.
 */
public final class SynapseS0 implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter driveMeter;

    private Orchestrator orchestrator;

    public SynapseS0(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.driveMeter = metrics.task("drive", 0);
    }

    @Override
    public void start() {
        orchestrator = Orchestrator.create("bench-s0", LogSink.SILENT);
        orchestrator.registerNode("drive", new DriveNode(orchestrator));
        GamepadAdaptor.attach(orchestrator, world.gamepad1(), "g1");
    }

    @Override
    public void stop() {
        if (orchestrator != null) {
            orchestrator.close();
            orchestrator = null;
        }
    }

    private final class DriveNode extends Node {
        DriveNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/left_stick_y")
        public void onStick(Float value) {
            metrics.countLoopIteration();
            driveMeter.tick();
            double power = value;
            orchestrator.hardware().run(() -> world.leftMotor().setPower(power));
        }
    }
}
