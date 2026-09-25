package com.aaravlabs.synapse.bench.shared;

/**
 * Deterministic ~3 ms of real pixel work over a 320x240 buffer. Identical kernel
 * for every style: repeated gradient passes over the frame until the target
 * duration is reached (so the load profile does not depend on CPU speed), with a
 * deterministic alignment result that is a pure function of the frame sequence.
 */
public final class SyntheticVisionPipeline {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;
    public static final long TARGET_NANOS = 3_000_000L;

    private SyntheticVisionPipeline() {
    }

    /**
     * Process one frame. Returns the alignment offset the robot must apply —
     * a deterministic function of {@code seq} so every style sees identical output.
     */
    public static double process(byte[] frame, long seq) {
        long start = System.nanoTime();
        long deadline = start + TARGET_NANOS;
        double acc = 0;
        int w = WIDTH;
        int h = HEIGHT;
        do {
            for (int yRow = 1; yRow < h - 1; yRow++) {
                int row = yRow * w;
                for (int x = 1; x < w - 1; x++) {
                    int i = row + x;
                    int g = (frame[i - 1] & 0xff) + (frame[i + 1] & 0xff)
                            + (frame[i - w] & 0xff) + (frame[i + w] & 0xff);
                    acc += (g * 0.25) * 0.001 + (frame[i] & 0xff) * 0.0005;
                }
            }
        } while (System.nanoTime() - deadline < 0);
        Blackhole.consume(acc);
        return Math.sin(seq * 0.17) * 3.0;
    }
}
