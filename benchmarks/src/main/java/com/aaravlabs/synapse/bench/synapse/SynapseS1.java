package com.aaravlabs.synapse.bench.synapse;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;

/**
 * S1 in idiomatic Synapse: the common rookie TeleOp. Tank drive off the
 * {@code GamepadAdaptor} axis topics, intake toggle on bumper rising/falling
 * edges, servo on button press, telemetry publish at 10 Hz.
 */
public final class SynapseS1 implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter driveMeter;
    private final TaskMeter telemetryMeter;

    private Orchestrator orchestrator;

    public SynapseS1(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.driveMeter = metrics.task("drive", 0);
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
    }

    @Override
    public void start() {
        orchestrator = Orchestrator.create("bench-s1", LogSink.SILENT);
        orchestrator.registerNode("drive", new DriveNode(orchestrator));
        orchestrator.registerNode("intake", new IntakeNode(orchestrator));
        orchestrator.registerNode("servo", new ServoNode(orchestrator));
        orchestrator.registerNode("telemetry", new TelemetryNode(orchestrator));
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
        public void onLeftY(Float value) {
            metrics.countLoopIteration();
            driveMeter.tick();
            double power = value;
            orchestrator.hardware().run(() -> world.leftMotor().setPower(power));
        }

        @SubscribedTo(topic = "g1/right_stick_y")
        public void onRightY(Float value) {
            metrics.countLoopIteration();
            double power = value;
            orchestrator.hardware().run(() -> world.rightMotor().setPower(power));
        }
    }

    private final class IntakeNode extends Node {
        private boolean running;

        IntakeNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/right_bumper/rising")
        @OnHardwareThread
        public void onPress(Boolean ignored) {
            metrics.countLoopIteration();
            running = true;
            world.intakeMotor().setPower(1.0);
        }

        @SubscribedTo(topic = "g1/right_bumper/falling")
        @OnHardwareThread
        public void onRelease(Boolean ignored) {
            metrics.countLoopIteration();
            running = false;
            world.intakeMotor().setPower(0.0);
        }

        boolean isRunning() {
            return running;
        }
    }

    private final class ServoNode extends Node {
        private boolean open;

        ServoNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/x/rising")
        @OnHardwareThread
        public void onToggle(Boolean ignored) {
            metrics.countLoopIteration();
            open = !open;
            world.servo().setPosition(open ? 1.0 : 0.0);
        }
    }

    private final class TelemetryNode extends Node {
        TelemetryNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @RunPeriodically(hz = 10)
        public void publishTelemetry() {
            metrics.countLoopIteration();
            telemetryMeter.tick();
            orchestrator.publish("telemetry/servo", world.servo().getPosition());
            world.telemetry().update();
        }
    }
}
