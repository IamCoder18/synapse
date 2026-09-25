package com.aaravlabs.synapse.bench.shared;

/**
 * The complexity ladder. Each scenario is the same workload hand-written in every
 * style; feature flags here keep the shared world identical across styles.
 */
public enum Scenario {
    S0_MinimalDrive,
    S1_BasicTeleop,
    S2_MultiSubsystem,
    S3_HeavyRobot;

    public static Scenario parse(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("S0")) return S0_MinimalDrive;
        if (t.equalsIgnoreCase("S1")) return S1_BasicTeleop;
        if (t.equalsIgnoreCase("S2")) return S2_MultiSubsystem;
        if (t.equalsIgnoreCase("S3")) return S3_HeavyRobot;
        for (Scenario v : values()) {
            if (v.name().equalsIgnoreCase(t)) return v;
        }
        throw new IllegalArgumentException("unknown scenario " + s);
    }

    public boolean hasServo() {
        return this == S1_BasicTeleop;
    }

    public boolean hasIntake() {
        return this != S0_MinimalDrive;
    }

    public boolean hasLift() {
        return this == S2_MultiSubsystem || this == S3_HeavyRobot;
    }

    public boolean hasTwoGamepads() {
        return this == S2_MultiSubsystem || this == S3_HeavyRobot;
    }

    public boolean hasAutoAlign() {
        return this == S2_MultiSubsystem || this == S3_HeavyRobot;
    }

    public boolean hasVision() {
        return this == S3_HeavyRobot;
    }

    public boolean hasSlowLogger() {
        return this == S3_HeavyRobot;
    }

    public boolean hasHeadingHold() {
        return this == S3_HeavyRobot;
    }

    /** S2/S3 publish a 50 Hz robot-state stream the slow logger consumes. */
    public boolean hasStatePublisher() {
        return this == S3_HeavyRobot;
    }

    /** Target rate for the drive task; 0 means "every pump iteration". */
    public double driveTargetHz() {
        return (this == S2_MultiSubsystem || this == S3_HeavyRobot) ? 50.0 : 0.0;
    }

    public double liftTargetHz() {
        return 100.0;
    }

    public double headingTargetHz() {
        return 200.0;
    }

    public double telemetryTargetHz() {
        return this == S0_MinimalDrive ? 0.0 : 10.0;
    }

    public double stateTargetHz() {
        return 50.0;
    }

    /** Honesty variant is meaningful once expensive side work exists. */
    public boolean hasRawmt() {
        return this == S2_MultiSubsystem || this == S3_HeavyRobot;
    }
}
