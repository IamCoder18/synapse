# Synapse recipes

Copy-paste patterns **verified on a real robot** — the 23684 Canopy-Biobuzz competition robot (PR #3, `feat/mecanum-teleop`, verified bench + field). Variables come from a `SafeOpMode` subclass (`orchestrator`, `hardware`, `safeMap`, `gamepad1`, `gamepad2`) or from the node's inherited `orchestrator` field — keep those names when adapting.

## Recipe 1 — Mecanum drive (robot-centric)

Three files. The OpMode wires hardware and renders telemetry; the node does the driving. Device names must match the Robot Configuration.

### `Subsystems/Drive.java`

```java
package org.firstinspires.ftc.teamcode.Subsystems;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.ftc.SafeDevice;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

public class Drive extends Node {

    private final SafeDevice<DcMotorEx> fl;
    private final SafeDevice<DcMotorEx> fr;
    private final SafeDevice<DcMotorEx> bl;
    private final SafeDevice<DcMotorEx> br;

    public Drive(
            Orchestrator orch,
            SafeDevice<DcMotorEx> frontLeft,
            SafeDevice<DcMotorEx> frontRight,
            SafeDevice<DcMotorEx> backLeft,
            SafeDevice<DcMotorEx> backRight) {
        super(orch);
        this.fl = frontLeft;
        this.fr = frontRight;
        this.bl = backLeft;
        this.br = backRight;

        fl.run(m -> m.setDirection(DcMotor.Direction.FORWARD));
        bl.run(m -> m.setDirection(DcMotor.Direction.FORWARD));
        fr.run(m -> m.setDirection(DcMotorSimple.Direction.REVERSE));
        br.run(m -> m.setDirection(DcMotorSimple.Direction.REVERSE));
    }

    @RunPeriodically(hz = 50, hardware = true)
    public void drive() {
        double y = -orchestrator.getLatestValue("g1/left_stick_y", Float.class).map(Float::doubleValue).orElse(0.0);
        double x = orchestrator.getLatestValue("g1/left_stick_x", Float.class).map(Float::doubleValue).orElse(0.0) * 1.1;
        double r = -orchestrator.getLatestValue("g1/right_stick_x", Float.class).map(Float::doubleValue).orElse(0.0);

        double pFL = y + x + r;
        double pFR = y - x - r;
        double pBL = y - x + r;
        double pBR = y + x - r;
        double max = Math.max(Math.abs(y) + Math.abs(x) + Math.abs(r), 1);

        fl.run(m -> m.setPower(pFL / max));
        fr.run(m -> m.setPower(pFR / max));
        bl.run(m -> m.setPower(pBL / max));
        br.run(m -> m.setPower(pBR / max));

        orchestrator.publish("drive/power/fl", pFL / max);
        orchestrator.publish("drive/power/fr", pFR / max);
        orchestrator.publish("drive/power/bl", pBL / max);
        orchestrator.publish("drive/power/br", pBR / max);
    }
}
```

Why it is written this way:

- **`hardware = true`** — the loop touches four motors every 20 ms; it must run on the hardware thread.
- **Directions in the constructor** via `fl.run(...)` — enqueued to the hardware thread during `onSafeInit`, fixed before the first loop tick. `setDirection` is per-robot wiring; this robot's right side is reversed.
- **gm0-style mix** with `y = -left_stick_y` (sticks rest at −1 forward), strafe scaled `× 1.1` (sideways mecanum inefficiency), `r = -right_stick_x`.
- **Single normalization** by `max(|y| + |x| + |r|, 1)` keeps all four powers in `[-1, 1]` while preserving the commanded direction. Do NOT normalize each wheel by its own maximum — that tilts the motion direction at high deflection.
- **Per-wheel powers are published** (`drive/power/*`) so the OpMode's telemetry renders them with `getLatestValue` and never touches hardware.

### `OpModes/TeleOp/MainTeleOp.java`

```java
package org.firstinspires.ftc.teamcode.OpModes.TeleOp;

import com.aaravlabs.synapse.ftc.GamepadAdaptor;
import com.aaravlabs.synapse.ftc.SafeDevice;
import com.aaravlabs.synapse.ftc.SafeOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.firstinspires.ftc.teamcode.Subsystems.Drive;

@TeleOp(name = "Main TeleOp", group = "TeleOp")
public class MainTeleOp extends SafeOpMode {

    private SafeDevice<DcMotorEx> frontLeft;
    private SafeDevice<DcMotorEx> frontRight;
    private SafeDevice<DcMotorEx> backLeft;
    private SafeDevice<DcMotorEx> backRight;

    @Override
    protected void onSafeInit() {
        frontLeft = safeMap.device(DcMotorEx.class, "frontLeft");
        frontRight = safeMap.device(DcMotorEx.class, "frontRight");
        backLeft = safeMap.device(DcMotorEx.class, "backLeft");
        backRight = safeMap.device(DcMotorEx.class, "backRight");

        GamepadAdaptor.attach(orchestrator, gamepad1, "g1");

        orchestrator.registerNode("drive",
                new Drive(orchestrator, frontLeft, frontRight, backLeft, backRight));
    }

    @Override
    protected void onSafeLoop() {
        telemetry.addData("FL", "%.2f", orchestrator.getLatestValue("drive/power/fl", Double.class).orElse(0.0));
        telemetry.addData("FR", "%.2f", orchestrator.getLatestValue("drive/power/fr", Double.class).orElse(0.0));
        telemetry.addData("BL", "%.2f", orchestrator.getLatestValue("drive/power/bl", Double.class).orElse(0.0));
        telemetry.addData("BR", "%.2f", orchestrator.getLatestValue("drive/power/br", Double.class).orElse(0.0));
        telemetry.update();
    }
}
```

### `OpModes/Tests/MotorDirectionTest.java`

Run on the bench, wheels up. Hold each button; each motor spins forward. A wrong-spinning motor gets its `setDirection` flipped in `Drive`'s constructor.

```java
package org.firstinspires.ftc.teamcode.OpModes.Tests;

import com.aaravlabs.synapse.ftc.GamepadAdaptor;
import com.aaravlabs.synapse.ftc.SafeDevice;
import com.aaravlabs.synapse.ftc.SafeOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorEx;

@TeleOp(name = "Motor Direction Test", group = "Tests")
public class MotorDirectionTest extends SafeOpMode {

    private SafeDevice<DcMotorEx> frontLeft;
    private SafeDevice<DcMotorEx> frontRight;
    private SafeDevice<DcMotorEx> backLeft;
    private SafeDevice<DcMotorEx> backRight;

    @Override
    protected void onSafeInit() {
        frontLeft = safeMap.device(DcMotorEx.class, "frontLeft");
        frontRight = safeMap.device(DcMotorEx.class, "frontRight");
        backLeft = safeMap.device(DcMotorEx.class, "backLeft");
        backRight = safeMap.device(DcMotorEx.class, "backRight");

        GamepadAdaptor.attach(orchestrator, gamepad1, "g1");

        orchestrator.subscribe("g1/x", Boolean.class, v -> frontLeft.run(m -> m.setPower(v ? 1.0 : 0.0)));
        orchestrator.subscribe("g1/a", Boolean.class, v -> backLeft.run(m -> m.setPower(v ? 1.0 : 0.0)));
        orchestrator.subscribe("g1/y", Boolean.class, v -> frontRight.run(m -> m.setPower(v ? 1.0 : 0.0)));
        orchestrator.subscribe("g1/b", Boolean.class, v -> backRight.run(m -> m.setPower(v ? 1.0 : 0.0)));
    }

    @Override
    protected void onSafeLoop() {
        telemetry.addData("frontLeft", "X");
        telemetry.addData("backLeft", "A");
        telemetry.addData("frontRight", "Y");
        telemetry.addData("backRight", "B");
        telemetry.update();
    }
}
```

Note the pattern: **programmatic `orchestrator.subscribe(...)` in `onSafeInit`** is the right tool for a throwaway test OpMode — no node class needed. The handlers route through `frontLeft.run(...)` so the writes still respect the hardware thread.

## Recipe 2 — Two-controller teleop (driver + operator)

Attach a second adaptor with a different prefix; each subsystem decides which roots it reads. The intake takes the **max** of the operator's stick and the driver's trigger — the driver can always run the intake at full regardless of operator input.

```java
// In onSafeInit():
GamepadAdaptor.attach(orchestrator, gamepad1, "g1");
GamepadAdaptor.attach(orchestrator, gamepad2, "g2");
```

### `Subsystems/Intake.java`

```java
package org.firstinspires.ftc.teamcode.Subsystems;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.ftc.SafeDevice;
import com.qualcomm.robotcore.hardware.DcMotorEx;

public class Intake extends Node {

    private final SafeDevice<DcMotorEx> intake;

    public Intake(Orchestrator orch, SafeDevice<DcMotorEx> intake) {
        super(orch);
        this.intake = intake;
    }

    @RunPeriodically(hz = 50, hardware = true)
    public void runIntake() {
        double operator = -orchestrator.getLatestValue("g2/left_stick_y", Float.class).map(Float::doubleValue).orElse(0.0);
        double trigger = orchestrator.getLatestValue("g1/left_trigger", Float.class).map(Float::doubleValue).orElse(0.0);
        double power = Math.max(operator, trigger);
        intake.run(m -> m.setPower(power));
        orchestrator.publish("intake/power", power);
    }
}
```

Register both subsystems in `onSafeInit()`:

```java
orchestrator.registerNode("drive", new Drive(orchestrator, frontLeft, frontRight, backLeft, backRight));
orchestrator.registerNode("intake", new Intake(orchestrator, intake));
```

## Template — button-edge subscriber

```java
@SubscribedTo(topic = "g1/right_bumper/rising")
@OnHardwareThread
public void grab(Boolean v) {
    claw.run(s -> s.setPosition(CLOSED));
}

@SubscribedTo(topic = "g1/right_bumper/falling")
@OnHardwareThread
public void release(Boolean v) {
    claw.run(s -> s.setPosition(OPEN));
}
```

## Template — named action with a future

```java
// In a node:
@RunnableAction("shooter/launch")
public void launch() {
    orchestrator.publish("shooter/set/speed", 4800.0);
}

// From onSafeLoop() or another callback:
orchestrator.runAction("shooter/launch");
```

## Template — hold writes until the match timer starts

```java
// In onSafeInit(): hold every hardware write until the match timer starts.
hardware.restrict("write", GateMode.QUEUE);

// Writes carry the label; a slot keeps the hold collapsed to the newest setpoint.
fl.labels("write").slot("fl-power").run(m -> m.setPower(pFL));

// In a node: release from a button-edge subscriber.
@SubscribedTo(topic = "g1/x/rising")
@OnHardwareThread
public void matchStart(Boolean v) {
    orchestrator.hardware().release("write");
}
```

- DROP variant — `hardware.restrict("write", GateMode.DROP)` discards writes instead of holding (work that is worthless once delayed); `hardware.restrict("write").drop().sealChildren().apply()` makes it a master switch nothing below may renegotiate.
- Slots collapse the hold: at most one pending action per slot, so a 50 Hz stream held for 30 s releases as one write per slot — never 1500 stale ones.
- For a `@RunPeriodically(hz = 50, hardware = true, labels = {"write"})` loop, ticks are simply skipped while covered and resume at release — nothing accumulates at all.

## Template — freeze a subsystem during an action

```java
// From a subscriber or onSafeLoop(): freeze the shooter for the whole macro.
hardware.restrict("shooter").queue().except("shooter/write").apply();
orchestrator.runAction("shooter/macro")
        .whenComplete((v, t) -> hardware.release("shooter"));
```

- `except("shooter/write")` carves the macro's own labels out of **this** restriction only (other restrictions still apply), so the macro's writes pass while the rest of the subsystem is held. Add `.sealChildren()` if nothing below may renegotiate the freeze.
- Topic-driven variant: instead of a macro's duration, restrict in a state subscriber (e.g. `shooter/state` flipping to spin-up/calibration) and release when it flips back.
- `whenComplete` fires on success *and* failure — the release always happens; anything dropped or skipped while restricted is summarised in one WARN at release.

## Supported but no robot-validated recipe yet

The library **fully supports** the patterns below — the APIs are stable and documented (`references/api-surface.md`). They are held out of this file only because the recipe has not yet been run on hardware. When asked for them, build from the documented building blocks and say plainly that the example has not been verified on a robot (do not present generated code as tested):

- Field-centric mecanum drive — IMU via `safeMap.device(IMU.class, ...)`, heading rotation before the mix, `SafeDevice.raw()` for IMU reads inside the hardware loop
- Bulk sensor reads — `HardwareActions.bulkRead(hz, reader)` with `HardwareView.publish`
- Autonomous skeleton — state machine in a `@RunPeriodically` loop over topics
- Emergency stop — `hardware.restrict(HardwareGate.ALL, GateMode.DROP).sealChildren().apply()` freezes every hardware action instantly
- Gate sequencing (the two Templates above) — library-verified (`HardwareGateTest`), but the patterns have not yet been run on a robot
