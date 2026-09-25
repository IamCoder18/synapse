package com.aaravlabs.synapse.bench.shared;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Driver-station telemetry at the I/O boundary: captions and values are
 * formatted into a size-capped batch and serialized on {@code update()}, the
 * way the real {@code Telemetry} pipeline builds a DS packet. The string work
 * is deliberate allocation on the message path so GC pauses land in the
 * actuation tail under a small heap, like they do on a Control Hub.
 */
public final class SimTelemetry implements Telemetry {

    public static final class Item implements Telemetry.Item {
    }

    private static final int MAX_CHARS = 2048;

    private final Item item = new Item();
    private final StringBuilder batch = new StringBuilder(256);
    private volatile long updates;
    private volatile long bytes;
    private volatile String lastPacket = "";

    @Override
    public Item addData(String caption, Object value) {
        if (batch.length() < MAX_CHARS) {
            batch.append(caption).append(": ").append(value).append('\n');
        }
        return item;
    }

    @Override
    public void update() {
        lastPacket = batch.toString();
        bytes += lastPacket.length();
        batch.setLength(0);
        updates++;
    }

    public long updates() {
        return updates;
    }

    public long bytes() {
        return bytes;
    }

    public String lastPacket() {
        return lastPacket;
    }
}
