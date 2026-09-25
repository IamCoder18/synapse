package com.aaravlabs.synapse.bench.shared;

/**
 * Volatile sink that defeats dead-code elimination in micro benchmarks.
 */
public final class Blackhole {

    private static volatile long sink;

    private Blackhole() {
    }

    public static void consume(long v) {
        sink = v;
    }

    public static void consume(double v) {
        sink = Double.doubleToRawLongBits(v);
    }

    public static long sink() {
        return sink;
    }
}
