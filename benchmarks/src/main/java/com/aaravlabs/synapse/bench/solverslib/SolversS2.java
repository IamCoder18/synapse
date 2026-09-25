package com.aaravlabs.synapse.bench.solverslib;

import com.aaravlabs.synapse.bench.shared.BusyWork;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.SharedPidf;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.seattlesolvers.solverslib.command.CommandBase;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.RunCommand;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

/**
 * S2 in idiomatic SolversLib: drive + intake + lift PIDF + outtake on two
 * gamepads, mixed target rates, 0.5 ms auto-align on a button command. Intake
 * and outtake are commands with the same requirement so the real scheduler
 * interruption machinery is exercised.
 */
public final class SolversS2 implements PairRunner {

    private static final long DRIVE_PERIOD_NANOS = 20_000_000L;
    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter pumpMeter;
    private final TaskMeter driveMeter;
    private final TaskMeter liftMeter;
    private final TaskMeter telemetryMeter;

    private final SharedPidf liftPidf = SharedPidf.forLift();

    private GamepadEx gamepad1Ex;
    private GamepadEx gamepad2Ex;
    private DriveSubsystem drive;
    private LiftSubsystem lift;
    private IntakeSubsystem intake;
    private AutoAlignCommand autoAlign;
    private IntakeCommand intakeCommand;
    private OuttakeCommand outtakeCommand;
    private TeleopDriveCommand teleopDrive;

    private volatile double alignOffset;
    private long alignSeq;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public SolversS2(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.pumpMeter = metrics.task("schedulerRun", 0);
        this.driveMeter = metrics.task("drive", world.scenario().driveTargetHz());
        this.liftMeter = metrics.task("liftPidf", world.scenario().liftTargetHz());
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
        intake = new IntakeSubsystem();
        autoAlign = new AutoAlignCommand();
        intakeCommand = new IntakeCommand();
        outtakeCommand = new OuttakeCommand();
        teleopDrive = new TeleopDriveCommand();

        CommandScheduler.getInstance().registerSubsystem(drive, lift, intake);
        CommandScheduler.getInstance().setDefaultCommand(drive, teleopDrive);

        gamepad1Ex.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER).toggleWhenPressed(intakeCommand);
        gamepad2Ex.getGamepadButton(GamepadKeys.Button.LEFT_BUMPER).whileHeld(outtakeCommand);
        gamepad1Ex.getGamepadButton(GamepadKeys.Button.A).whenPressed(autoAlign);

        thread = new Thread(this::pump, "solvers-s2");
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
                world.telemetry().addData("lift", world.plant().liftPos);
                world.telemetry().update();
            }
        }
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
        CommandScheduler.getInstance().reset();
        CommandScheduler.getInstance().clearButtons();
    }

    private final class DriveSubsystem extends SubsystemBase {
        void drive(double leftY, double rightY, double turn, double align) {
            world.leftMotor().setPower(leftY + turn + align);
            world.rightMotor().setPower(rightY - turn - align);
        }
    }

    private final class LiftSubsystem extends SubsystemBase {
        private long last;

        void setPower(double power) {
            world.liftMotor().setPower(power);
        }

        @Override
        public void periodic() {
            long now = System.nanoTime();
            if (now - last < 10_000_000L) return;
            last = now;
            liftMeter.tick(now);
            double target = world.setpoints().liftTargetAt(now);
            setPower(liftPidf.update(world.plant().liftPos, target, now));
        }
    }

    private final class IntakeSubsystem extends SubsystemBase {
        void setPower(double power) {
            world.intakeMotor().setPower(power);
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
            drive.drive(gamepad1Ex.getLeftY(), -gamepad1Ex.getRightY(), gamepad1Ex.getLeftX(), alignOffset);
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
            alignOffset = BusyWork.autoAlign(alignSeq++) * 0.02;
        }

        @Override
        public boolean isFinished() {
            return true;
        }
    }
}
