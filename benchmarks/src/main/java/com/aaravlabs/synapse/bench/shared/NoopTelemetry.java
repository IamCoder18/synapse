package com.aaravlabs.synapse.bench.shared;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * No-op driver-station telemetry at the I/O boundary (there is no driver station
 * on a desktop JVM). Calling it keeps the raw/SolversLib styles on the real
 * {@code Telemetry} API shape.
 */
public final class NoopTelemetry implements Telemetry {

    public static final class Item implements Telemetry.Item {
    }

    private final Item item = new Item();
    private volatile long updates;

    @Override
    public Item addData(String caption, Object value) {
        return item;
    }

    @Override
    public void update() {
        updates++;
    }

    public long updates() {
        return updates;
    }
}
