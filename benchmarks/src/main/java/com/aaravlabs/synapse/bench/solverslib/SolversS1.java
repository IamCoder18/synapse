package com.aaravlabs.synapse.bench.solverslib;

import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.InstantCommand;
import com.seattlesolvers.solverslib.command.RunCommand;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;
import com.seattlesolvers.solverslib.gamepad.GamepadKeys;

/**
 * S1 in idiomatic SolversLib: the common rookie TeleOp. Tank drive default
 * command, intake toggle and servo on real {@code GamepadButton.whenPressed}
 * bindings (through {@code CommandScheduler.addButton}), telemetry at 10 Hz.
 */
public final class SolversS1 implements PairRunner {

    private static final long TELEMETRY_PERIOD_NANOS = 100_000_000L;

    private final World world;
    private final Metrics metrics;
    private final TaskMeter pumpMeter;
    private final TaskMeter telemetryMeter;

    private GamepadEx gamepadEx;
    private DriveSubsystem drive;
    private IntakeSubsystem intake;
    private ServoSubsystem servo;
    private TelemetrySubsystem telemetrySubsystem;

    private boolean intakeRunning;
    private boolean servoOpen;
    private long lastTelemetry;

    private volatile boolean active;
    private Thread thread;

    public SolversS1(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.pumpMeter = metrics.task("schedulerRun", 0);
        this.telemetryMeter = metrics.task("telemetry", world.scenario().telemetryTargetHz());
    }

    @Override
    public void start() {
        active = true;
        CommandScheduler.getInstance().reset();

        gamepadEx = new GamepadEx(world.gamepad1());
        drive = new DriveSubsystem();
        intake = new IntakeSubsystem();
        servo = new ServoSubsystem();
        telemetrySubsystem = new TelemetrySubsystem();

        CommandScheduler.getInstance().setDefaultCommand(drive, new RunCommand(() -> {
            drive.setPower(gamepadEx.getLeftY(), -gamepadEx.getRightY());
        }, drive));

        gamepadEx.getGamepadButton(GamepadKeys.Button.RIGHT_BUMPER).whenPressed(new InstantCommand(() -> {
            intakeRunning = !intakeRunning;
            intake.setPower(intakeRunning ? 1.0 : 0.0);
        }, intake));

        gamepadEx.getGamepadButton(GamepadKeys.Button.X).whenPressed(new InstantCommand(() -> {
            servoOpen = !servoOpen;
            servo.setPosition(servoOpen ? 1.0 : 0.0);
        }, servo));

        thread = new Thread(this::pump, "solvers-s1");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        CommandScheduler scheduler = CommandScheduler.getInstance();
        while (active) {
            gamepadEx.readButtons();
            scheduler.run();
            pumpMeter.tick();
            metrics.countLoopIteration();
            long now = System.nanoTime();
            if (now - lastTelemetry >= TELEMETRY_PERIOD_NANOS) {
                lastTelemetry = now;
                telemetryMeter.tick(now);
                world.telemetry().addData("intake", intakeRunning ? "on" : "off");
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
        void setPower(double left, double right) {
            world.leftMotor().setPower(left);
            world.rightMotor().setPower(right);
        }
    }

    private final class IntakeSubsystem extends SubsystemBase {
        void setPower(double power) {
            world.intakeMotor().setPower(power);
        }
    }

    private final class ServoSubsystem extends SubsystemBase {
        void setPosition(double pos) {
            world.servo().setPosition(pos);
        }
    }

    private final class TelemetrySubsystem extends SubsystemBase {
    }
}
