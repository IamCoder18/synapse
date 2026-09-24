package com.aaravlabs.synapse.bench.shared;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Input-to-actuation latency probe. The stimulus side stamps {@link #stimulus} just
 * before flipping a {@code Gamepad} field (or declaring a frame ready) and declares
 * the expected direction of the motor power that will carry the new input; the
 * actuation side stamps {@link #actuation} from {@code SimMotor.setPower}/{@code
 * SimServo.setPosition} which run inside framework-invoked code. A pending stimulus
 * pairs only with a write that crosses the threshold in the expected direction (so a
 * stale or correction-dominated write cannot steal the pairing) and is retained until
 * a qualifying write occurs.
 */
public final class LatencyProbe {

    private final String name;
    private final AtomicLong pendingT0 = new AtomicLong();
    private final Hist hist = new Hist();
    private volatile boolean recording;
    private volatile double sign;

    public LatencyProbe(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void setRecording(boolean on) {
        if (!on) pendingT0.set(0);
        recording = on;
    }

    public void reset() {
        pendingT0.set(0);
        hist.reset();
    }

    /**
     * Called by the stimulus thread immediately before the input write.
     *
     * @param expectedSign expected direction of the motor power that carries
     *                     this input (+1 / -1, style sign convention applied)
     */
    public void stimulus(long t0, double expectedSign) {
        if (recording) {
            sign = expectedSign;
            pendingT0.set(t0);
        }
    }

    /** Called by {@code SimMotor.setPower}/{@code SimServo.setPosition}. */
    public void actuation(long t1, double power) {
        if (pendingT0.get() == 0 || power * sign < 0.2) return;
        long t0 = pendingT0.getAndSet(0);
        if (t0 != 0 && recording) {
            hist.record(t1 - t0);
        }
    }

    public Hist hist() {
        return hist;
    }
}
