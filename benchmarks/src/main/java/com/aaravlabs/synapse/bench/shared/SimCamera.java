package com.aaravlabs.synapse.bench.shared;

import java.util.function.Consumer;

/**
 * Simulated camera at the physical I/O boundary: a capture thread produces
 * timestamped frames at 30 Hz into a ring of reusable buffers. Styles either pull
 * the latest unconsumed frame or receive push callbacks (Synapse publishes the
 * frame onto its bus from this thread). Frame generation is world-side; only the
 * processing kernel ({@link SyntheticVisionPipeline}) runs on a measured path.
 */
public final class SimCamera implements AutoCloseable {

    public static final int HZ = 30;
    public static final int RING = 8;
    private static final long PERIOD_NANOS = 1_000_000_000L / HZ;

    /** A single reusable frame buffer. */
    public static final class Frame {
        public final byte[] buf = new byte[SyntheticVisionPipeline.WIDTH * SyntheticVisionPipeline.HEIGHT];
        public volatile long seq = -1;
        public volatile long tNanos;

        public Frame copy() {
            Frame f = new Frame();
            System.arraycopy(buf, 0, f.buf, 0, buf.length);
            f.seq = seq;
            f.tNanos = tNanos;
            return f;
        }
    }

    private final Frame[] ring = new Frame[RING];
    private volatile int latestSlot = -1;
    private volatile long consumedSeq = -1;
    private volatile Consumer<Frame> listener;
    private volatile boolean running;
    private Thread thread;

    public SimCamera() {
        for (int i = 0; i < RING; i++) ring[i] = new Frame();
    }

    /** Push mode: invoked on the capture thread for every produced frame. */
    public void setListener(Consumer<Frame> listener) {
        this.listener = listener;
    }

    public void start() {
        running = true;
        thread = new Thread(this::captureLoop, "sim-camera");
        thread.setDaemon(true);
        thread.start();
    }

    private void captureLoop() {
        long seq = 0;
        long next = System.nanoTime();
        while (running) {
            int slot = (int) (seq % RING);
            Frame f = ring[slot];
            fill(f.buf, seq);
            f.seq = seq;
            f.tNanos = System.nanoTime();
            latestSlot = slot;
            Consumer<Frame> l = listener;
            if (l != null) l.accept(f);
            seq++;
            next += PERIOD_NANOS;
            long now;
            while ((now = System.nanoTime()) - next < 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(next - now, 50_000L));
            }
        }
    }

    private static void fill(byte[] buf, long seq) {
        int s = (int) (seq * 2654435761L);
        for (int i = 0; i < buf.length; i += 7) {
            buf[i] = (byte) (s + i);
        }
    }

    /** Pull mode: latest not-yet-consumed frame, or null. */
    public Frame pollFrame() {
        int slot = latestSlot;
        if (slot < 0) return null;
        Frame f = ring[slot];
        long seq = f.seq;
        if (seq <= consumedSeq) return null;
        consumedSeq = seq;
        return f;
    }

    public Frame latestFrame() {
        int slot = latestSlot;
        return slot < 0 ? null : ring[slot];
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
