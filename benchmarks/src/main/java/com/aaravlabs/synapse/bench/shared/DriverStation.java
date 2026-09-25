package com.aaravlabs.synapse.bench.shared;

import java.util.ArrayDeque;

/**
 * Driver Station link model: gamepad updates reach the robot on a ~25 ms
 * packet cadence, not the instant the driver moves. A dropped packet defers
 * everything queued to the next packet (never silently discards input), which
 * is what a flaky DS link does to button edges and stick steps. Drop decisions
 * are a pure hash of (seed, packet index) so every style sees the identical
 * link. Queue drains run the field writes on this single thread, preserving
 * the {@code Gamepad} single-writer shape.
 */
public final class DriverStation implements AutoCloseable {

    public static final long PACKET_NANOS = 25_000_000L;

    private final long seed;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private volatile boolean running;
    private Thread thread;

    public DriverStation(long seed) {
        this.seed = seed;
    }

    /** Queue a gamepad mutation for delivery on the next good DS packet. */
    public void post(Runnable packet) {
        synchronized (queue) {
            queue.addLast(packet);
        }
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "driver-station");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long t0 = System.nanoTime();
        long tick = 0;
        while (running) {
            tick++;
            long due = t0 + tick * PACKET_NANOS;
            long now;
            while ((now = System.nanoTime()) - due < 0) {
                if (!running) return;
                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(due - now, 5_000_000L));
            }
            if (dropped(tick)) {
                continue;
            }
            while (true) {
                Runnable packet;
                synchronized (queue) {
                    packet = queue.pollFirst();
                }
                if (packet == null) {
                    break;
                }
                packet.run();
            }
        }
    }

    private boolean dropped(long tick) {
        long h = seed * 0x9E3779B97F4A7C15L + tick * 0xD1B54A32D192ED03L;
        h ^= h >>> 31;
        return (h & 31L) == 0;
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
