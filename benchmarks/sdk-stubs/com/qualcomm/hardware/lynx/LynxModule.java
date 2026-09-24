package com.qualcomm.hardware.lynx;

/**
 * Link-only synthetic SDK type. The published SolversLib {@code CommandScheduler}
 * references {@code LynxModule} in {@code setBulkReading}/{@code run}; the checked-in
 * {@code ftc-sdk-stub.jar} does not carry it. This stub exists purely so those methods
 * can link on a desktop JVM. It is never called on a measured path.
 */
public class LynxModule {

    public enum BulkCachingMode {
        MANUAL,
        AUTO
    }

    public void setBulkCachingMode(BulkCachingMode mode) {
    }

    public void clearBulkCache() {
    }
}
