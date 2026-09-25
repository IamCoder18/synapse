package com.aaravlabs.synapse.bench.shared;

/**
 * Lynx-class device bus model: one shared serialized link, every transaction
 * pays a fixed transfer cost while holding the bus. Costs are desktop-scaled
 * (single-digit to tens of microseconds, not USB milliseconds) so the
 * framework dispatch ladder stays measurable, while the *structure* is
 * robot-real: writes and reads contend for one link, single-register reads
 * cost more per register than a bulk read. Spin-based, deterministic load.
 *
 * <p>Device-write cost lives here and is only paid by {@link SimMotor}/{@link
 * SimServo} instances that were given a bus; the mock-budget gate's fixture
 * measures the bare volatile mock write with no bus attached.
 */
public final class LynxBus {

    public static final long WRITE_NANOS = 3_000L;
    public static final long READ_NANOS = 5_000L;
    public static final long BULK_BASE_NANOS = 4_000L;
    public static final long BULK_REG_NANOS = 1_000L;

    private final Object link = new Object();
    private volatile long sink;

    public void write() {
        tx(WRITE_NANOS);
    }

    public void read() {
        tx(READ_NANOS);
    }

    /** Bulk read of N registers: one transaction, cheaper than N single reads. */
    public void bulkRead(int regs) {
        tx(BULK_BASE_NANOS + BULK_REG_NANOS * regs);
    }

    private void tx(long nanos) {
        synchronized (link) {
            long deadline = System.nanoTime() + nanos;
            long x = 0x9E3779B97F4A7C15L;
            long now;
            while ((now = System.nanoTime()) - deadline < 0) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
            }
            sink = x;
        }
    }
}
