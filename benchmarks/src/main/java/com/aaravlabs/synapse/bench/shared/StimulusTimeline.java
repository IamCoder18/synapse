package com.aaravlabs.synapse.bench.shared;

import com.qualcomm.robotcore.hardware.Gamepad;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Seeded, deterministic stimulus: gamepad field flips on a fixed timeline,
 * identical per style per scenario. Events are queued to the
 * {@link DriverStation} packet link and only become visible to the robot when
 * a packet is flushed; the stimulus stamp is taken at flush time, right before
 * the write to the real stub {@link Gamepad} volatile fields — that stamp is
 * the "input" end of input-to-actuation latency, exactly like a driver whose
 * stick move has to survive the DS link first.
 */
public final class StimulusTimeline implements AutoCloseable {

    private static final class Event {
        final long atNanos;
        final Runnable apply;

        Event(long atNanos, Runnable apply) {
            this.atNanos = atNanos;
            this.apply = apply;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;

    private void at(double seconds, Runnable apply) {
        events.add(new Event((long) (seconds * 1e9), apply));
    }

    /**
     * Build the workload timeline covering the full warmup-plus-measure span.
     * Stick steps are latency-probe events (the measured input→actuation path);
     * button/edge traffic exercises toggles and command conflicts.
     *
     * @param spanSec        total warmup-plus-measure duration the timeline must cover
     * @param leftPowerSign  style sign convention mapping a forward-positive stick
     *                       command to the expected motor-power direction
     *                       (−1 raw/rawmt/synapse write the raw gamepad field,
     *                       +1 solverslib's {@code GamepadEx.getLeftY()} negates it)
     */
    public static StimulusTimeline build(Scenario scenario, long seed,
                                        Gamepad g1, Gamepad g2,
                                        LatencyProbe actuation,
                                        DriverStation ds,
                                        double spanSec, double leftPowerSign) {
        StimulusTimeline t = new StimulusTimeline();
        Random rnd = new Random(seed);
        int steps = (int) Math.floor((spanSec - 0.41) / 0.30);
        for (int i = 0; i < steps; i++) {
            double mag = 0.4 + 0.6 * rnd.nextDouble();
            // Alternate the sign every step so a stale (previous-step) write has
            // the wrong direction and cannot pair with the new stimulus.
            final double leftY = ((i & 1) == 0 ? 0.85 : -0.85) * mag;
            // Heading-hold scenarios command translation only (matched sticks) so
            // the hold loop is fighting disturbances, not the driver.
            final double rightY = scenario.hasHeadingHold() ? leftY
                    : (rnd.nextBoolean() ? 0.85 : -0.85) * (0.4 + 0.6 * rnd.nextDouble());
            final double leftX = scenario.hasHeadingHold() ? 0.0
                    : (rnd.nextBoolean() ? 0.3 : -0.3) * rnd.nextDouble();
            // Jittered cadence keeps stimulus events from phase-locking with the
            // styles' fixed task periods (a 300 ms cadence is exactly 15× 20 ms
            // and would measure a constant phase offset instead of latency).
            final double atSec = 0.30 + i * 0.30 + rnd.nextDouble() * 0.11;
            final double expectedSign = leftPowerSign * Math.signum(leftY);
            t.at(atSec, () -> ds.post(() -> {
                actuation.stimulus(System.nanoTime(), expectedSign);
                g1.left_stick_y = (float) -leftY;
                g1.right_stick_y = (float) -rightY;
                g1.left_stick_x = (float) leftX;
            }));
        }

        if (scenario.hasIntake()) {
            for (int i = 0; 0.55 + i * 0.45 + 0.07 < spanSec; i++) {
                final boolean down = (i % 2 == 0);
                final double atSec = 0.55 + i * 0.45 + rnd.nextDouble() * 0.07;
                t.at(atSec, () -> ds.post(() -> g1.right_bumper = down));
            }
        }

        if (scenario.hasServo()) {
            for (int i = 0; 0.80 + i * 0.55 + 0.09 < spanSec; i++) {
                final boolean down = (i % 2 == 0);
                final double atSec = 0.80 + i * 0.55 + rnd.nextDouble() * 0.09;
                t.at(atSec, () -> ds.post(() -> g1.x = down));
            }
        }

        if (scenario.hasAutoAlign()) {
            for (int i = 0; 0.65 + i * 0.50 + 0.08 < spanSec; i++) {
                final boolean down = (i % 2 == 0);
                final double atSec = 0.65 + i * 0.50 + rnd.nextDouble() * 0.08;
                t.at(atSec, () -> ds.post(() -> g1.a = down));
            }
        }

        if (scenario.hasTwoGamepads()) {
            for (int i = 0; 0.70 + i * 0.55 + 0.06 < spanSec; i++) {
                final boolean down = (i % 2 == 0);
                final double atSec = 0.70 + i * 0.55 + rnd.nextDouble() * 0.06;
                t.at(atSec, () -> ds.post(() -> g2.left_bumper = down));
            }
        }

        t.events.sort((a, b) -> Long.compare(a.atNanos, b.atNanos));
        return t;
    }

    public void start() {
        running = true;
        List<Event> plan = new ArrayList<>(events);
        thread = new Thread(() -> {
            long t0 = System.nanoTime();
            for (Event e : plan) {
                if (!running) return;
                long due = t0 + e.atNanos;
                long now;
                while ((now = System.nanoTime()) - due < 0) {
                    LockSupport.parkNanos(Math.min(due - now, 100_000L));
                    if (!running) return;
                }
                e.apply.run();
            }
        }, "stimulus");
        thread.setDaemon(true);
        thread.start();
    }

    public int eventCount() {
        return events.size();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
