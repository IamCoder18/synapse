package com.aaravlabs.synapse.bench.solverslib;

import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.TaskMeter;
import com.aaravlabs.synapse.bench.shared.World;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.RunCommand;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

/**
 * S0 in idiomatic SolversLib: one stick → one motor power via a default
 * {@link RunCommand} on a {@link SubsystemBase}, pumped by
 * {@code CommandScheduler.run()} (the idiomatic OpMode loop).
 */
public final class SolversS0 implements PairRunner {

    private final World world;
    private final Metrics metrics;
    private final TaskMeter pumpMeter;

    private GamepadEx gamepadEx;
    private DriveSubsystem drive;
    private volatile boolean active;
    private Thread thread;

    public SolversS0(World world) {
        this.world = world;
        this.metrics = world.metrics();
        this.pumpMeter = metrics.task("schedulerRun", 0);
    }

    @Override
    public void start() {
        active = true;
        CommandScheduler.getInstance().reset();

        gamepadEx = new GamepadEx(world.gamepad1());
        drive = new DriveSubsystem();

        CommandScheduler.getInstance().setDefaultCommand(drive,
                new RunCommand(() -> drive.setPower(gamepadEx.getLeftY()), drive));

        thread = new Thread(this::pump, "solvers-s0");
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
        void setPower(double power) {
            world.leftMotor().setPower(power);
        }

        @Override
        public void periodic() {
        }
    }
}
