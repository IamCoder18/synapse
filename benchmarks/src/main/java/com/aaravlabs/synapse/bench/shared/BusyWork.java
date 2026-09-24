package com.aaravlabs.synapse.bench.shared;

/**
 * Deterministic CPU work used for the scenario "expensive" kernels (auto-align,
 * slow debug logger). Busy-spins on real arithmetic for a fixed duration so the
 * load profile is machine-independent, and returns a stable blackhole sink.
 */
public final class BusyWork {

    /** Moderate ~0.5 ms auto-align kernel (S2/S3 gamepad event work). */
    public static final long AUTO_ALIGN_NANOS = 500_000L;

    /** Slow debug logger kernel (S3): 15 ms of work per state-update event. */
    public static final long SLOW_LOGGER_NANOS = 15_000_000L;

    private static volatile long sink;

    private BusyWork() {
    }

    /** Spin doing integer work for the requested duration. Allocation-free. */
    public static void spinNanos(long nanos) {
        long start = System.nanoTime();
        long deadline = start + nanos;
        long x = 0x9E3779B97F4A7C15L;
        long acc = 0;
        long now;
        while ((now = System.nanoTime()) - deadline < 0) {
            for (int i = 0; i < 64; i++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                acc += x;
            }
        }
        sink = acc;
    }

    /** Auto-align kernel: fixed-duration work, deterministic result. */
    public static double autoAlign(long eventSeq) {
        spinNanos(AUTO_ALIGN_NANOS);
        return Math.sin(eventSeq * 0.37) * 4.0;
    }

    /** Slow logger kernel: fixed-duration work per event. */
    public static void slowLog(long eventSeq) {
        spinNanos(SLOW_LOGGER_NANOS);
        sink = eventSeq;
    }

    public static long sink() {
        return sink;
    }
}
