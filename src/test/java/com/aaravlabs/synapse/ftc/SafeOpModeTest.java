package com.aaravlabs.synapse.ftc;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Gamepad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    /** Tracks every OpMode initialized by a test so its orchestrator is closed. */
    private final List<SafeOpMode> initialized = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SafeOpMode op : initialized) {
            op.stop();
        }
        initialized.clear();
    }

    @Test
    void teleOpDefaultsPublishGamepadsTrue() {
        SafeOpMode op = new TeleOpDefault();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertTrue(op.publishGamepads,
                "@TeleOp opmode should default publishGamepads to true");
        assertNotNull(op.orchestrator, "orchestrator should be initialized");
    }

    @Test
    void autoDefaultsPublishGamepadsFalse() {
        SafeOpMode op = new AutoDefault();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertFalse(op.publishGamepads,
                "Autonomous opmode should default publishGamepads to false");
    }

    @Test
    void teleOpAttachesGamepadAdaptorByDefault() {
        SafeOpMode op = new TeleOpDefault();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertTrue(op.publishGamepads);
        assertTrue(op.orchestrator.findTopic("g1/a", Boolean.class).isPresent(),
                "gamepad topics should be created for @TeleOp by default");
    }

    @Test
    void autoDoesNotAttachGamepadAdaptorByDefault() {
        SafeOpMode op = new AutoDefault();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertFalse(op.publishGamepads);
        assertFalse(op.orchestrator.findTopic("g1/a", Boolean.class).isPresent(),
                "no gamepad topics should be created for Auto by default");
    }

    @Test
    void teleOpOptOutDisablesGamepadPublishing() {
        SafeOpMode op = new TeleOpOptOut();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertFalse(op.publishGamepads,
                "TeleOp with publishGamepads=false should not publish gamepads");
        assertFalse(op.orchestrator.findTopic("g1/a", Boolean.class).isPresent(),
                "no gamepad topics should be created when opted out");
    }

    @Test
    void autoOptInEnablesGamepadPublishing() {
        SafeOpMode op = new AutoOptIn();
        op.gamepad1 = new Gamepad();
        op.init();
        initialized.add(op);
        assertTrue(op.publishGamepads,
                "Auto opmode with publishGamepads=true should publish gamepads");
        assertTrue(op.orchestrator.findTopic("g1/a", Boolean.class).isPresent(),
                "gamepad topics should be created when opted in");
    }
}
