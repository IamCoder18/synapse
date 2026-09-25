package com.aaravlabs.synapse.bench.synapse;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.RunnableAction;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.SimCamera;
import com.aaravlabs.synapse.bench.shared.SyntheticVisionPipeline;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;

/**
 * S3 in idiomatic Synapse: everything from S2 plus 30 Hz vision on the callback
 * pool, two PIDF loops on the hardware thread (lift 100 Hz, heading hold 200 Hz)
 * and a slow debug logger subscribed to 50 Hz state updates on the callback pool.
 * The classic "one slow consumer" is isolated from control here.
 */
public final class SynapseS3 implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter headingMeter;
    private final TaskMeter visionMeter;
    private final TaskMeter loggerMeter;
    private final TaskMeter telemetryMeter;
    private final TaskMeter alignMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();
    private final SharedPidf headingPidf = SharedPidf.forHeading();

    private Orchestrator orchestrator;

    public SynapseS3(World world) {
        this.world = world;
        this.metrics = world.metrics();
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
        orchestrator = Orchestrator.create("bench-s3", LogSink.SILENT);
        orchestrator.registerNode("drive", new DriveNode(orchestrator));
        orchestrator.registerNode("lift", new LiftNode(orchestrator));
        orchestrator.registerNode("vision", new VisionNode(orchestrator));
        orchestrator.registerNode("logger", new DebugLogNode(orchestrator));
        orchestrator.registerNode("intake", new IntakeNode(orchestrator));
        orchestrator.registerNode("align", new AlignNode(orchestrator));
        orchestrator.registerNode("telemetry", new TelemetryNode(orchestrator));
        GamepadAdaptor.attach(orchestrator, world.gamepad1(), "g1");
        GamepadAdaptor.attach(orchestrator, world.gamepad2(), "g2");
        world.camera().setListener(frame -> orchestrator.publish("camera/frame", frame.copy()));
    }

    @Override
    public void stop() {
        if (orchestrator != null) {
            world.camera().setListener(null);
            orchestrator.close();
            orchestrator = null;
        }
    }

    private final class DriveNode extends Node {
        private volatile double leftY;
        private volatile double rightY;
        private volatile double cmdY;
        private volatile double cmdRightY;

        DriveNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "g1/left_stick_y")
        public void onLeftY(Float v) {
            metrics.countLoopIteration();
            leftY = v;
        }

        @SubscribedTo(topic = "g1/right_stick_y")
        public void onRightY(Float v) {
            metrics.countLoopIteration();
            rightY = v;
        }

        @SubscribedTo(topic = "align/offset")
        public void onAlign(Double v) {
            metrics.countLoopIteration();
            world.plant().headingBias = v;
        }

        @RunPeriodically(hz = 50, hardware = true)
        public void sampleSticks() {
            metrics.countLoopIteration();
            driveMeter.tick();
            cmdY = leftY;
            cmdRightY = rightY;
        }

        @RunPeriodically(hz = 200, hardware = true)
        public void headingHold() {
            metrics.countLoopIteration();
            headingMeter.tick();
            long now = System.nanoTime();
            double target = world.setpoints().headingTargetAt(now) + world.plant().headingBias;
            double corr = headingPidf.update(world.sensors().heading(), target, now);
            world.leftMotor().setPower(cmdY - corr);
            world.rightMotor().setPower(cmdRightY + corr);
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

    private final class VisionNode extends Node {
        VisionNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "camera/frame")
        public void onFrame(SimCamera.Frame frame) {
            metrics.countLoopIteration();
            visionMeter.tick();
            double offset = SyntheticVisionPipeline.process(frame.buf, frame.seq) * 0.002;
            orchestrator.publish("align/offset", offset);
        }
    }

    private final class DebugLogNode extends Node {
        DebugLogNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @SubscribedTo(topic = "robot/state")
        public void onState(Long seq) {
            metrics.countLoopIteration();
            loggerMeter.tick();
            BusyWork.slowLog(seq);
        }
    }

    private final class IntakeNode extends Node {
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
            orchestrator.hardware().run(() -> world.intakeMotor().setPower(1.0));
        }

        @RunnableAction("outtake")
        public void outtake() {
            metrics.countLoopIteration();
            orchestrator.hardware().run(() -> world.intakeMotor().setPower(-1.0));
        }

        @RunnableAction("intake-off")
        public void intakeOff() {
            metrics.countLoopIteration();
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
            double offset = BusyWork.autoAlign(seq++) * 0.002;
            orchestrator.publish("align/offset", offset);
        }
    }

    private final class TelemetryNode extends Node {
        private long stateSeq;

        TelemetryNode(Orchestrator orchestrator) {
            super(orchestrator);
        }

        @RunPeriodically(hz = 50)
        public void publishState() {
            metrics.countLoopIteration();
            orchestrator.publish("robot/state", ++stateSeq);
        }

        @RunPeriodically(hz = 10)
        public void publishTelemetry() {
            metrics.countLoopIteration();
            telemetryMeter.tick();
            orchestrator.publish("telemetry/lift", world.sensors().liftPos());
            world.telemetry().update();
        }
    }
}
