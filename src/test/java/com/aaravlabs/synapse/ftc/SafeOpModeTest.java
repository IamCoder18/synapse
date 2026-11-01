package com.aaravlabs.synapse.ftc;

import com.aaravlabs.synapse.Orchestrator;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SafeOpMode} defaults gamepad publishing off for
 * Autonomous/other opmodes (opt-in) and on for {@link TeleOp} opmodes
 * (optionally disabled), and that {@link SafeOpMode#attachGamepad(Object, String)}
 * respects the {@link SafeOpMode#publishGamepads} flag.
 */
class SafeOpModeTest {

    @TeleOp(name = "TeleOp Default")
    private static class TeleOpDefault extends SafeOpMode {
        @Override
        protected void onSafeInit() {
            attachGamepad(gamepad1, "g1");
        }
    }

    private static class AutoDefault extends SafeOpMode {
        @Override
        protected void onSafeInit() {
            attachGamepad(gamepad1, "g1");
        }
    }

    @TeleOp(name = "TeleOp Opt Out")
    private static class TeleOpOptOut extends SafeOpMode {
        @Override
        protected void onSafeInit() {
            publishGamepads = false; // opt out
            attachGamepad(gamepad1, "g1");
        }
    }

    private static class AutoOptIn extends SafeOpMode {
        @Override
        protected void onSafeInit() {
            publishGamepads = true; // opt in
            attachGamepad(gamepad1, "g1");
        }
    }

    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = FtcOrchestrator.create();
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void teleOpDefaultsPublishGamepadsTrue() {
        SafeOpMode op = new TeleOpDefault();
        op.init();
        assertTrue(op.publishGamepads,
                "@TeleOp opmode should default publishGamepads to true");
        assertNotNull(op.orchestrator, "orchestrator should be initialized");
    }

    @Test
    void autoDefaultsPublishGamepadsFalse() {
        SafeOpMode op = new AutoDefault();
        op.init();
        assertFalse(op.publishGamepads,
                "Autonomous opmode should default publishGamepads to false");
    }

    @Test
    void teleOpAttachesGamepadAdaptorByDefault() {
        SafeOpMode op = new TeleOpDefault();
        op.init();
        assertTrue(op.publishGamepads);
        assertNotNull(op.orchestrator.findTopic("g1/a", Boolean.class),
                "gamepad topics should be created for @TeleOp by default");
    }

    @Test
    void autoDoesNotAttachGamepadAdaptorByDefault() {
        SafeOpMode op = new AutoDefault();
        op.init();
        assertFalse(op.publishGamepads);
        assertNull(op.orchestrator.findTopic("g1/a", Boolean.class).orElse(null),
                "no gamepad topics should be created for Auto by default");
    }

    @Test
    void teleOpOptOutDisablesGamepadPublishing() {
        SafeOpMode op = new TeleOpOptOut();
        op.init();
        assertFalse(op.publishGamepads,
                "TeleOp with publishGamepads=false should not publish gamepads");
        assertNull(op.orchestrator.findTopic("g1/a", Boolean.class).orElse(null),
                "no gamepad topics should be created when opted out");
    }

    @Test
    void autoOptInEnablesGamepadPublishing() {
        SafeOpMode op = new AutoOptIn();
        op.init();
        assertTrue(op.publishGamepads,
                "Auto opmode with publishGamepads=true should publish gamepads");
        assertNotNull(op.orchestrator.findTopic("g1/a", Boolean.class),
                "gamepad topics should be created when opted in");
    }
}
