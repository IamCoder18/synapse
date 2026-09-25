package com.aaravlabs.synapse.bench.solverslib;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.SimCamera;
import com.aaravlabs.synapse.bench.shared.SyntheticVisionPipeline;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.seattlesolvers.solverslib.command.CommandBase;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

/**
 * S3 in idiomatic SolversLib: everything from S2 plus 30 Hz vision (~3 ms/frame
 * in {@code VisionSubsystem.periodic()}) and a slow debug logger (15 ms per
 * state update) — all serialized by the single {@code CommandScheduler.run()}
 * pump. Expected to degrade like raw FTC.
 */
public final class SolversS3 implements PairRunner {

    private static final long HEADING_PERIOD_NANOS = 5_000_000L;
    private static final long DRIVE_PERIOD_NANOS = 20_000_000L;
    private static final long STATE_PERIOD_NANOS = 20_000_000L;
    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter pumpMeter;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter headingMeter;
    private final TaskMeter visionMeter;
    private final TaskMeter loggerMeter;
    private final TaskMeter telemetryMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();
    private final SharedPidf headingPidf = SharedPidf.forHeading();

    private GamepadEx gamepad1Ex;
    private GamepadEx gamepad2Ex;
    private DriveSubsystem drive;
    private LiftSubsystem lift;
    private HeadingSubsystem heading;
    private IntakeSubsystem intake;
    private VisionSubsystem vision;
    private DebugLogSubsystem debugLog;
    private TeleopDriveCommand teleopDrive;
    private IntakeCommand intakeCommand;
    private OuttakeCommand outtakeCommand;
    private AutoAlignCommand autoAlign;

    private volatile double stickY;
    private volatile double stickRightY;
    private long alignSeq;
    private long stateSeq;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public SolversS3(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.pumpMeter = metrics.task("schedulerRun", 0);
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
        CommandScheduler.getInstance().reset();

        gamepad1Ex = new GamepadEx(world.gamepad1());
        gamepad2Ex = new GamepadEx(world.gamepad2());
        drive = new DriveSubsystem();
        lift = new LiftSubsystem();
        heading = new HeadingSubsystem();
        intake = new IntakeSubsystem();
        vision = new VisionSubsystem();
        debugLog = new DebugLogSubsystem();
        teleopDrive = new TeleopDriveCommand();
        intakeCommand = new IntakeCommand();
        outtakeCommand = new OuttakeCommand();
        autoAlign = new AutoAlignCommand();

        CommandScheduler.getInstance().registerSubsystem(drive, lift, heading, intake, vision, debugLog);
        CommandScheduler.getInstance().setDefaultCommand(drive, teleopDrive);

        gamepad1Ex.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER).toggleWhenPressed(intakeCommand);
        gamepad2Ex.getGamepadButton(GamepadKeys.Button.LEFT_BUMPER).whileHeld(outtakeCommand);
        gamepad1Ex.getGamepadButton(GamepadKeys.Button.A).whenPressed(autoAlign);

        thread = new Thread(this::pump, "solvers-s3");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        CommandScheduler scheduler = CommandScheduler.getInstance();
        while (active) {
            gamepad1Ex.readButtons();
            gamepad2Ex.readButtons();
            scheduler.run();
            pumpMeter.tick();
            metrics.countLoopIteration();
            long now = System.nanoTime();
            if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
                lastTelemetry = now;
                telemetryMeter.tick(now);
                world.telemetry().addData("lift", world.sensors().liftPos());
                world.telemetry().addData("heading", world.sensors().heading());
                world.telemetry().update();
            }
        }
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
        CommandScheduler.getInstance().reset();
        CommandScheduler.getInstance().clearButtons();
    }

    private final class DriveSubsystem extends SubsystemBase {
    }

    private final class LiftSubsystem extends SubsystemBase {
        private long last;

        @Override
        public void periodic() {
            long now = System.nanoTime();
            if (now - last < 10_000_000L) return;
            last = now;
            liftMeter.tick(now);
            double target = world.setpoints().liftTargetAt(now);
            world.liftMotor().setPower(liftPidf.update(world.sensors().liftPos(), target, now));
        }
    }

    private final class HeadingSubsystem extends SubsystemBase {
        private long last;

        @Override
        public void periodic() {
            long now = System.nanoTime();
            if (now - last < HEADING_PERIOD_NANOS) return;
            last = now;
            headingMeter.tick(now);
            double target = world.setpoints().headingTargetAt(now) + world.plant().headingBias;
            double corr = headingPidf.update(world.sensors().heading(), target, now);
            world.leftMotor().setPower(stickY - corr);
            world.rightMotor().setPower(stickRightY + corr);
        }
    }

    private final class IntakeSubsystem extends SubsystemBase {
        void setPower(double power) {
            world.intakeMotor().setPower(power);
        }
    }

    private final class VisionSubsystem extends SubsystemBase {
        @Override
        public void periodic() {
            SimCamera.Frame frame = world.camera().pollFrame();
            if (frame == null) return;
            visionMeter.tick();
            world.plant().headingBias = SyntheticVisionPipeline.process(frame.buf, frame.seq) * 0.002;
        }
    }

    private final class DebugLogSubsystem extends SubsystemBase {
        private long last;

        @Override
        public void periodic() {
            long now = System.nanoTime();
            if (now - last < STATE_PERIOD_NANOS) return;
            last = now;
            stateSeq++;
            loggerMeter.tick(now);
            BusyWork.slowLog(stateSeq);
        }
    }

    private final class TeleopDriveCommand extends CommandBase {
        private long last;

        TeleopDriveCommand() {
            addRequirements(drive);
        }

        @Override
        public void execute() {
            long now = System.nanoTime();
            if (now - last < DRIVE_PERIOD_NANOS) return;
            last = now;
            driveMeter.tick(now);
            stickY = gamepad1Ex.getLeftY();
            // GamepadEx.getLeftY() is forward-positive (it negates the raw field) but
            // getRightY() is raw-gamepad-signed — normalize so matched sticks drive
            // matched wheels in every style's plant.
            stickRightY = -gamepad1Ex.getRightY();
        }

        @Override
        public boolean isFinished() {
            return false;
        }
    }

    private final class IntakeCommand extends CommandBase {
        IntakeCommand() {
            addRequirements(intake);
        }

        @Override
        public void initialize() {
            intake.setPower(1.0);
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void end(boolean interrupted) {
            intake.setPower(0.0);
        }
    }

    private final class OuttakeCommand extends CommandBase {
        OuttakeCommand() {
            addRequirements(intake);
        }

        @Override
        public void initialize() {
            intake.setPower(-1.0);
        }

        @Override
        public void execute() {
            intake.setPower(-1.0);
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void end(boolean interrupted) {
            intake.setPower(0.0);
        }
    }

    private final class AutoAlignCommand extends CommandBase {
        @Override
        public void initialize() {
            world.plant().headingBias = BusyWork.autoAlign(alignSeq++) * 0.002;
        }

        @Override
        public boolean isFinished() {
            return true;
        }
    }
}
