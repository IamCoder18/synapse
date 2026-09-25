package com.aaravlabs.synapse.bench.shared;

import java.util.Arrays;

/**
 * Preallocated percentile histogram. Single-writer: {@link #record} must only be
 * called from one thread at a time (the thread that owns the probed path).
 * Constant-cost, allocation-free on the measured path.
 */
public final class Hist {

    public static final class Snapshot {
        public final long p50;
        public final long p90;
        public final long p99;
        public final long max;
        public final long min;
        public final double mean;
        public final long count;

        Snapshot(long p50, long p90, long p99, long max, long min, double mean, long count) {
            this.p50 = p50;
            this.p90 = p90;
            this.p99 = p99;
            this.max = max;
            this.min = min;
            this.mean = mean;
            this.count = count;
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0.0, 0);
        }
    }

    private final long[] buf;
    private long total;
    private volatile int n;

    public Hist() {
        this(1 << 16);
    }

    public Hist(int capacity) {
        this.buf = new long[Math.max(16, capacity)];
    }

    public void record(long value) {
        long i = total++;
        buf[(int) (i % buf.length)] = value;
        n = (int) Math.min(total, buf.length);
    }

    public void reset() {
        total = 0;
        n = 0;
    }

    public int count() {
        return n;
    }

    public Snapshot snapshot() {
        int count = n;
        if (count == 0) return Snapshot.empty();
        long[] copy = Arrays.copyOf(buf, count);
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) sum += v;
        return new Snapshot(
                percentile(copy, 0.50),
                percentile(copy, 0.90),
                percentile(copy, 0.99),
                copy[copy.length - 1],
                copy[0],
                ((double) sum) / count,
                count);
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double rank = p * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return (long) Math.round(sorted[lo] * (1.0 - frac) + sorted[hi] * frac);
    }
}
