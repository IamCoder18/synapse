package com.aaravlabs.synapse.bench.shared;

/**
 * Per-task achieved-rate / period-jitter meter. {@link #tick} is called from inside
 * the periodic body the framework invokes ({@code loop()} body,
 * {@code Command.execute()}, {@code @RunPeriodically} method). Single-writer.
 */
public final class TaskMeter {

    private final String name;
    private final double targetHz;
    private final Hist periods = new Hist();
    private volatile boolean recording;
    private long last;
    private long count;
    private long firstTick;

    public TaskMeter(String name, double targetHz) {
        this.name = name;
        this.targetHz = targetHz;
    }

    public String name() {
        return name;
    }

    public double targetHz() {
        return targetHz;
    }

    public void setRecording(boolean on) {
        recording = on;
    }

    public void reset() {
        periods.reset();
        last = 0;
        count = 0;
        firstTick = 0;
    }

    public void tick() {
        tick(System.nanoTime());
    }

    public void tick(long now) {
        if (!recording) return;
        if (firstTick == 0) firstTick = now;
        if (last != 0) periods.record(now - last);
        last = now;
        count++;
    }

    public long count() {
        return count;
    }

    public Hist.Snapshot periodSnapshot() {
        return periods.snapshot();
    }

    /** Achieved Hz over the recording window, measured between first and last tick. */
    public double achievedHz() {
        if (count < 2 || firstTick == 0 || last <= firstTick) return 0.0;
        double seconds = (last - firstTick) / 1e9;
        return (count - 1) / seconds;
    }
}
