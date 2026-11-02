---
name: synapse-pubsub-ftc
description: Builds FIRST Tech Challenge (FTC) robot code on the Synapse annotation-driven pub/sub bus. ALWAYS use this skill for any FTC coding task — new OpModes, TeleOp or autonomous, subsystems, gamepad handling, sensors, refactoring raw OpModes, debugging races or hangs, or anything mentioning Synapse, @SubscribedTo, @RunPeriodically, @RunnableAction, @OnHardwareThread, SafeOpMode, HardwareActions, SafeDevice, SafeHardwareMap, GamepadAdaptor, or the Synapse orchestrator. For non-FTC code (FRC, generic Java, web, etc.) do not use this skill.
---

# Synapse on FTC

Synapse (Maven `com.aaravlabs:synapse`, package `com.aaravlabs.synapse`) gives FTC robot code a typed pub/sub bus and a single dedicated hardware thread. This skill is the canonical reference for writing, reviewing, and debugging Synapse OpModes. **Default to Synapse for every FTC task** — new code, refactors, bug fixes, ideas the user describes verbally. Only stop for non-FTC coding (FRC, generic Java, web work), in which case say so and work in the target framework instead.

The skill is mostly about **knowing which thread a piece of code will run on, and routing hardware access correctly**. Almost every Synapse bug is a thread or a type mismatch, and both are predictable before the code ever reaches a robot.

---

## The 8 rules. Read these first.

1. **All hardware access happens on the hardware thread. Nothing else.** Route it one of three ways: `@OnHardwareThread` on a `@SubscribedTo` method, `@RunPeriodically(hz = N, hardware = true)`, or `HardwareActions`/`SafeDevice` (`run`/`call`/`callAsync`) from anywhere else. A raw `motor.setPower(...)` anywhere else is a bug — fix it before anything else.

2. **Never block waiting for the hardware thread while on it.** Calling `hardware.call(...)` or `device.call(...)` from inside a `hardware = true` loop, an `@OnHardwareThread` subscriber, or a `bulkRead` reader **deadlocks the robot** — the single thread waits on a task it can never start. There, use `device.raw()` and touch the device directly. `call(...)` is only for callers *off* the hardware thread.

3. **Decide topic names and types before writing nodes.** One root per producer: `g1/...` (gamepad 1), `g2/...` (gamepad 2), `drive/...`, `intake/...`. Declare shared topics with `getOrCreateTopic(name, Type.class)` so the type is pinned in one place — the first publish otherwise locks the type from the value's runtime class (an `int` literal makes the topic `Integer` forever).

4. **Gamepad axes are `Float`.** Read them as `getLatestValue("g1/left_stick_y", Float.class).map(Float::doubleValue).orElse(0.0)`. Fetching with `Double.class` silently returns an empty `Optional`. FTC sticks also rest at −1 when pushed forward, so invert: `double y = -(...)`.

5. **Write the full attribute.** `@SubscribedTo(topic = "x")` — the annotation has no shorthand, `@SubscribedTo("x")` does not compile. `@RunnableAction("fire")` is the exception (its value is the name).

6. **One node per subsystem.** A `Subsystems/Drive.java` node takes its `SafeDevice`s via the constructor, does its work in one `@RunPeriodically(hz = 50, hardware = true)` loop, and **publishes its state** (`drive/power/fl`) so telemetry never touches hardware. Register from `onSafeInit()` with the subsystem name; node names are unique — a duplicate name silently skips registration (warns in Logcat).

7. **Telemetry reads topics, not devices.** In `onSafeLoop()`, use `orchestrator.getLatestValue(...)`. Never call `getCurrentPosition()` or similar from the OpMode thread — that is exactly the race Synapse exists to prevent.

8. **Verify on the bench before the field.** Every new drivetrain gets a direction-test OpMode that spins each motor from one button (see `references/recipes.md`). Motor direction is wiring, not code — five minutes with wheels off the ground saves a match.

---

## Mental model

An FTC OpMode runs on the SDK's OpMode thread. `SafeOpMode.init()` additionally creates the orchestrator, which spins up **four workers**:

| Worker | Threads | Queue | Used for |
| --- | --- | --- | --- |
| **Scheduler** | 8 | unbounded | `@RunPeriodically` loops (non-hardware), `runPeriodically(...)` |
| **Callback pool** | 4–16 | bounded, 256 | `@SubscribedTo` handlers |
| **Action pool** | grows on demand | unbounded | `@RunnableAction` invocations |
| **Hardware thread** | **1 (dedicated)** | — | every hardware-touching operation |

Plus the OpMode thread itself, which runs the lifecycle. The full map:

| OpMode method | Your hook | What you do there |
| --- | --- | --- |
| `init()` | `onSafeInit()` (required) | create `SafeDevice`s, register nodes, attach `GamepadAdaptor`s via `attachGamepad()` |
| `init_loop()` | `onSafeInitLoop()` | rarely used |
| `start()` | `onSafeStart()` | one-shot start logic |
| `loop()` | `onSafeLoop()` | telemetry from topics only |
| `stop()` | `onSafeStop()` (before close) | flush telemetry; orchestrator closes right after |

Every thread is a daemon named `synapse-<name>-<n>`; the hardware thread is `synapse-ftc-hw-1` — filter Logcat by thread name when something hangs.

**Gamepad publishing defaults.** `SafeOpMode.init()` sets `publishGamepads` to `true` for `@TeleOp` OpModes and `false` for Autonomous/other. Use `attachGamepad(gamepad, "g1")` instead of calling `GamepadAdaptor.attach` directly so the default is respected. Set `publishGamepads = true` in `onSafeInit()` to opt in (auto), or `publishGamepads = false` to opt out (teleop).

**Publishing flow:** `publish()` updates the topic's latest-value cache *synchronously on the caller's thread*, then enqueues subscriber callbacks onto the callback pool (or the hardware thread for `@OnHardwareThread` subscribers). Publishing is always non-blocking — even from the hardware thread.

---

## Decision: which execution context?

| The code... | Route | How |
| --- | --- | --- |
| Repeats at a fixed rate **and** touches devices | hardware thread | `@RunPeriodically(hz = N, hardware = true)` |
| Repeats, no hardware | scheduler pool | `@RunPeriodically(hz = N)` |
| Reacts to a message, touches hardware | hardware thread | `@SubscribedTo` + `@OnHardwareThread` |
| Reacts to a message, no hardware | callback pool | `@SubscribedTo` alone |
| One-shot fired by name | action pool | `@RunnableAction("name")` + `orchestrator.runAction("name")` |
| Outside annotations — `onSafeInit`, `onSafeLoop`, glue | hardware thread via facade | `hardware.run(...)` / `device.run(...)` / `device.call(...)` |
| Already on the hardware thread | direct | `device.raw().someCall(...)` |

Quick test: does the method touch a `DcMotor*`, `Servo*`, `IMU`, CRServo, `ColorSensor`, `TouchSensor`, `DigitalChannel`, `LynxModule`, or any other `hardwareMap` device? Then it must end up on the hardware thread by one of the routes above. Gamepad reads, `telemetry`, and pure math never do.

## Decision: subscribe, poll, or action?

| Need | Use |
| --- | --- |
| React to an event (button press, setpoint change) | `@SubscribedTo(topic = "g1/a/rising")` — subscribe |
| Continuous control from a current value | `getLatestValue(...)` inside a `@RunPeriodically(hardware = true)` loop — poll |
| A named sequence the OpMode or a callback triggers | `@RunnableAction` — action |
| Telemetry in `onSafeLoop()` | `getLatestValue(...)` — poll |

> Buttons publish three topics: `g1/a` (current, every poll — fires 60×/s), `g1/a/rising` and `g1/a/falling` (fire `true` once per transition). Presses want `/rising`; held state wants the base topic.

## Decision: topic type

| Value | Type | Notes |
| --- | --- | --- |
| Gamepad axis / trigger / touchpad coordinate | `Float` | always; map to `double` for math |
| Button state, edges | `Boolean` | `/rising` and `/falling` publish only `true` |
| Powers, positions, sensor readings | `Double` | prefer `Double` over `Integer` for anything numeric you might scale later |
| Anything structured | your own class | auto-published; ensure the topic is declared before any other type publishes to it |

Primitives and wrappers are normalized (`double` ≡ `Double`); compatibility is assignability-based (a `Number` topic accepts `Integer` and `Double` publishes). Wrong-type **publish** throws `IllegalArgumentException`; wrong-type **read** returns an empty `Optional`; wrong-type **subscription parameter** silently never fires.

---

## The standard file layout

Copy this structure for any new OpMode — it is exactly what ships on a competition robot (see `references/recipes.md` for the full files):

```
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/
├── OpModes/TeleOp/MainTeleOp.java        extends SafeOpMode; wiring + telemetry
├── OpModes/Tests/MotorDirectionTest.java one button per motor
└── Subsystems/Drive.java                 extends Node; one concern
```

Skeleton OpMode:

```java
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

        attachGamepad(gamepad1, "g1");

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

Inside a node, the inherited field is `orchestrator` (never construct the bus yourself, never reference `hardwareMap` from a node — devices come in via the constructor).

---

## Silent behaviors the orchestrator applies

These happen without exceptions; know them or they will cost you a practice session:

| Trigger | What happens |
| --- | --- |
| `registerNode` with an already-used name | No-op — returns the prior node, logs a WARN. Second subsystem silently does nothing. |
| `@RunPeriodically` with `hz <= 0` | Logs a warning, loop never scheduled. |
| First `publish` to an unknown topic | Creates the topic from the value's runtime type — locks the type. |
| Publish a wrong-typed value to an existing topic | Throws `IllegalArgumentException` **on the publishing thread** — including the hardware thread. |
| Publish `null` | Throws `IllegalArgumentException`. |
| Publish after `close()` | Ignored with a warning. |
| `@SubscribedTo` param type doesn't match the message | **Silently dropped** — handler never runs, nothing logged. |
| Callback queue (256) full | Handler runs on the **publisher's** thread (`CallerRunsPolicy`) — a saturated bus degrades isolation instead of dropping work. |
| Exception in a subscriber / loop / action | Logged through the orchestrator sink; execution continues. |
| `@RunPeriodically` timing | Fixed delay: next run starts `1000/hz` ms after the previous *finishes* — effective rate ≤ `hz`; first run fires immediately at registration. |
| `@RunnableAction` duplicate name | First registration wins; second logs a warning and is skipped. |
| `cancelAllActions()` | Permanently shuts the action pool — every later `runAction` completes exceptionally. |
| `hardware.shutdown()` | Closes the **entire orchestrator**, despite the name. |
| `SafeOpMode.stop()` | Calls `onSafeStop()` then `orchestrator.close()` — node `close()` hooks run, all workers shut down. |

## Common mistakes

1. **Raw hardware call off-thread.** `motor.setPower(0.5)` in `onSafeLoop()`, a plain subscriber, or an action → race condition. Route it: `@OnHardwareThread`, `hardware.run(...)`, or `SafeDevice.run(...)`.
2. **`device.call(...)` inside hardware-thread code** → guaranteed deadlock (rule 2).
3. **`@SubscribedTo("x")` shorthand** → compile error. Use `topic = "x"`.
4. **`@SubscribedTo(topic = "...")` with the wrong param type** → never fires (silent drop). Check the declared type against what the publisher actually publishes.
5. **Axes fetched as `Double`** → always empty `Optional`. Fetch `Float.class`, map, invert.
6. **Duplicate node or action names** → second one silently skipped (warning in Logcat).
7. **Forgetting `hardware = true`** on a device-touching loop → runs on the scheduler pool where hardware access is forbidden.
8. **Second `Orchestrator.create(...)` inside an OpMode** → duplicate pools, broken lifecycle. `SafeOpMode` owns the bus.

---

## Reference files (load on demand)

Load the relevant reference **before** writing code in that area — they contain the exact flag/semantics gotchas:

- Writing or reviewing annotated methods → `references/annotations.md`
- Anything about threading, deadlocks, or "what runs where" → `references/safety.md`
- Copy-paste robot-validated OpModes (mecanum, intake, direction test) → `references/recipes.md`
- Converting an existing raw OpMode → `references/migration.md`
- Exact method signatures and sharp edges of every public type → `references/api-surface.md`

## Quick API surface

```text
com.aaravlabs.synapse
  Orchestrator        getOrCreateTopic / findTopic / publish / subscribe
                      getLatestValue / registerNode / unregisterNode / findNode
                      runAction / cancelAllActions / runPeriodically
                      runOnHardwareThread / hardware() / log-warn-error / close
  Node                base class; protected final Orchestrator orchestrator; close()
  Topic<T>            name / type / latestValue / latestValueOr / latestPublishNanos
  Subscription        unsubscribe()
  LogSink             STDERR / SILENT

com.aaravlabs.synapse.annotation
  @SubscribedTo(topic = "...")                  repeatable; 0-1 params
  @RunPeriodically(hz = 20, hardware = false)   repeatable; no params
  @RunnableAction("name")                       not repeatable; no params, void
  @OnHardwareThread                             modifier for @SubscribedTo

com.aaravlabs.synapse.ftc
  SafeOpMode         orchestrator / hardware / safeMap + 5 hooks
                      publishGamepads / attachGamepad(gamepad, "g1")
  FtcOrchestrator    create() / create(sink)
  HardwareActions    run / call / call(+timeout) / callAsync
                     bulkRead / stopBulkRead / isHardwareThread
                     assertNotHardwareThread / tick / shutdown(=closes ALL)
  SafeDevice<T>      run / call / callAsync / raw()
  SafeHardwareMap    device(Class, name) / raw()
  GamepadAdaptor     attach(orch, gamepad, "g1") -> "GamepadAdaptor:g1"
  BulkReader         read(HardwareView)     @FunctionalInterface
  HardwareView       publish(topic, value) / getLatestValue(topic, type)
```

Version note: docs pin the current release (e.g. `0.4.0`) only on the Install page; everywhere else say "the latest release" and link https://github.com/IamCoder18/synapse/releases.
