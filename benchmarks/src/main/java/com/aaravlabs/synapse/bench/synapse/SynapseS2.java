package com.aaravlabs.synapse.bench.synapse;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.RunnableAction;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;

/**
 * S2 in idiomatic Synapse: drive + intake + lift PIDF + outtake on two gamepads,
 * per-pool rates (drive 50 Hz and lift PIDF 100 Hz on the hardware thread,
 * auto-align and telemetry on the scheduler/callback pools). Intake and outtake
 * are real {@code RunnableAction}s fired from button edges.
 */
public final class SynapseS2 implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter telemetryMeter;
    private final TaskMeter alignMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();

    private Orchestrator orchestrator;

    public SynapseS2(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
        this.alignMeter = metrics.task("align", 0);
    }

    @Override
    public void start() {
        orchestrator = Orchestrator.create("bench-s2", LogSink.SILENT);
        orchestrator.registerNode("drive", new DriveNode(orchestrator));
        orchestrator.registerNode("lift", new LiftNode(orchestrator));
        orchestrator.registerNode("intake", new IntakeNode(orchestrator));
        orchestrator.registerNode("align", new AlignNode(orchestrator));
        orchestrator.registerNode("telemetry", new TelemetryNode(orchestrator));
        GamepadAdaptor.attach(orchestrator, world.gamepad1(), "g1");
        GamepadAdaptor.attach(orchestrator, world.gamepad2(), "g2");
    }

    @Override
    public void stop() {
        if (orchestrator != null) {
            orchestrator.close();
            orchestrator = null;
        }
    }

    private final class DriveNode extends Node {
        private volatile double leftY;
        private volatile double leftX;
        private volatile double rightY;
        private volatile double align;

        DriveNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/left_stick_y")
        public void onLeftY(Float v) {
            metrics.countLoopIteration();
            leftY = v;
        }

        @SubscribedTo(topic = "g1/left_stick_x")
        public void onLeftX(Float v) {
            metrics.countLoopIteration();
            leftX = v;
        }

        @SubscribedTo(topic = "g1/right_stick_y")
        public void onRightY(Float v) {
            metrics.countLoopIteration();
            rightY = v;
        }

        @SubscribedTo(topic = "align/offset")
        public void onAlign(Double v) {
            metrics.countLoopIteration();
            align = v;
        }

        @RunPeriodically(hz = 50, hardware = true)
        public void drive() {
            metrics.countLoopIteration();
            driveMeter.tick();
            double turn = leftX;
            world.leftMotor().setPower(leftY + turn + align);
            world.rightMotor().setPower(rightY - turn - align);
        }
    }

    private final class LiftNode extends Node {
        LiftNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @RunPeriodically(hz = 100, hardware = true)
        public void update() {
            metrics.countLoopIteration();
            liftMeter.tick();
            long now = System.nanoTime();
            double target = world.setpoints().liftTargetAt(now);
            double power = liftPidf.update(world.sensors().liftPos(), target, now);
            world.liftMotor().setPower(power);
        }
    }

    private final class IntakeNode extends Node {
        private boolean running;
        private long actionSeq;

        IntakeNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/right_bumper/rising")
        public void onIntakeToggle(Boolean ignored) {
            metrics.countLoopIteration();
            orchestrator.runAction("intake");
        }

        @SubscribedTo(topic = "g2/left_bumper/rising")
        public void onOuttakeStart(Boolean ignored) {
            metrics.countLoopIteration();
            orchestrator.runAction("outtake");
        }

        @SubscribedTo(topic = "g2/left_bumper/falling")
        public void onOuttakeStop(Boolean ignored) {
            metrics.countLoopIteration();
            orchestrator.runAction("intake-off");
        }

        @RunnableAction("intake")
        public void intake() {
            metrics.countLoopIteration();
            actionSeq++;
            running = true;
            orchestrator.hardware().run(() -> world.intakeMotor().setPower(1.0));
        }

        @RunnableAction("outtake")
        public void outtake() {
            metrics.countLoopIteration();
            actionSeq++;
            running = false;
            orchestrator.hardware().run(() -> world.intakeMotor().setPower(-1.0));
        }

        @RunnableAction("intake-off")
        public void intakeOff() {
            metrics.countLoopIteration();
            actionSeq++;
            running = false;
            orchestrator.hardware().run(() -> world.intakeMotor().setPower(0.0));
        }
    }

    private final class AlignNode extends Node {
        private long seq;

        AlignNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/a/rising")
        public void onAlignRequest(Boolean ignored) {
            metrics.countLoopIteration();
            alignMeter.tick();
            double offset = BusyWork.autoAlign(seq++) * 0.02;
            orchestrator.publish("align/offset", offset);
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
            double lift = world.sensors().liftPos();
            orchestrator.publish("telemetry/lift", lift);
            world.telemetry().addData("lift", lift);
            world.telemetry().update();
        }
    }
}
