package com.aaravlabs.synapse.bench.harness;

import com.aaravlabs.synapse.bench.raw.RawS0;
import com.aaravlabs.synapse.bench.raw.RawS1;
import com.aaravlabs.synapse.bench.raw.RawS2;
import com.aaravlabs.synapse.bench.raw.RawS3;
import com.aaravlabs.synapse.bench.rawmt.RawMtS2;
import com.aaravlabs.synapse.bench.rawmt.RawMtS3;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.Scenario;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.bench.solverslib.SolversS0;
import com.aaravlabs.synapse.bench.solverslib.SolversS1;
import com.aaravlabs.synapse.bench.solverslib.SolversS2;
import com.aaravlabs.synapse.bench.solverslib.SolversS3;
import com.aaravlabs.synapse.bench.synapse.SynapseS0;
import com.aaravlabs.synapse.bench.synapse.SynapseS1;
import com.aaravlabs.synapse.bench.synapse.SynapseS2;
import com.aaravlabs.synapse.bench.synapse.SynapseS3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps (scenario, style) to the hand-written pair implementation.
 */
public final class Registry {

    public static final String RAW = "raw";
    public static final String RAWMT = "rawmt";
    public static final String SOLVERSLIB = "solverslib";
    public static final String SYNAPSE = "synapse";

    public static final List<String> ALL_STYLES =
            Arrays.asList(RAW, RAWMT, SOLVERSLIB, SYNAPSE);

    private Registry() {
    }

    public static List<String> stylesFor(Scenario scenario) {
        List<String> styles = new ArrayList<>();
        styles.add(RAW);
        styles.add(SOLVERSLIB);
        styles.add(SYNAPSE);
        if (scenario.hasRawmt()) styles.add(RAWMT);
        return styles;
    }

    /**
     * Sign mapping a forward-positive stick command to the motor-power direction
     * each style writes. Raw/rawmt/synapse write the raw gamepad field
     * (up-negative) straight to the motor; SolversLib's
     * {@code GamepadEx.getLeftY()} negates it back to forward-positive.
     */
    public static double drivePowerSign(String style) {
        return SOLVERSLIB.equals(style) ? 1.0 : -1.0;
    }

    public static PairRunner create(Scenario scenario, String style, World world) {
        switch (style) {
            case RAW:
                switch (scenario) {
                    case S0_MinimalDrive:
                        return new RawS0(world);
                    case S1_BasicTeleop:
                        return new RawS1(world);
                    case S2_MultiSubsystem:
                        return new RawS2(world);
                    case S3_HeavyRobot:
                        return new RawS3(world);
                    default:
                        break;
                }
                break;
            case RAWMT:
                switch (scenario) {
                    case S2_MultiSubsystem:
                        return new RawMtS2(world);
                    case S3_HeavyRobot:
                        return new RawMtS3(world);
                    default:
                        break;
                }
                break;
            case SOLVERSLIB:
                switch (scenario) {
                    case S0_MinimalDrive:
                        return new SolversS0(world);
                    case S1_BasicTeleop:
                        return new SolversS1(world);
                    case S2_MultiSubsystem:
                        return new SolversS2(world);
                    case S3_HeavyRobot:
                        return new SolversS3(world);
                    default:
                        break;
                }
                break;
            case SYNAPSE:
                switch (scenario) {
                    case S0_MinimalDrive:
                        return new SynapseS0(world);
                    case S1_BasicTeleop:
                        return new SynapseS1(world);
                    case S2_MultiSubsystem:
                        return new SynapseS2(world);
                    case S3_HeavyRobot:
                        return new SynapseS3(world);
                    default:
                        break;
                }
                break;
            default:
                break;
        }
        throw new IllegalArgumentException("no implementation for " + scenario + " x " + style);
    }
}
