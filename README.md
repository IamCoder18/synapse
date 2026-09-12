# Synapse

[![Test](https://github.com/IamCoder18/synapse/actions/workflows/test.yml/badge.svg)](https://github.com/IamCoder18/synapse/actions/workflows/test.yml)
[![Publish](https://github.com/IamCoder18/synapse/actions/workflows/publish.yml/badge.svg)](https://github.com/IamCoder18/synapse/actions/workflows/publish.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/tag/IamCoder18/synapse?label=release)](https://github.com/IamCoder18/synapse/releases)
[![Maven Package](https://img.shields.io/badge/Maven-GitHub%20Packages-blue)](https://github.com/IamCoder18/synapse/packages)

A tiny, annotation-driven pub/sub bus for **FIRST** Tech Challenge robot code.

Write `Node`s that talk to each other through named, typed **topics** instead
of direct references. The orchestrator manages the threads, so `@SubscribedTo`
callbacks and `@RunPeriodically` loops never block each other, and **all
hardware-touching code runs on a single dedicated thread** — the only safe
way to use `DcMotorEx`, servos, sensors, and bulk reads in an FTC program.

> ~40 KB JAR. Zero runtime dependencies. R8-minify-safe.

```java
@TeleOp(name = "Demo", group = "Test")
public class DemoOpMode extends SafeOpMode {
    private SafeDevice<DcMotorEx> intake;

    @Override protected void onSafeInit() {
        intake = safeMap.device(DcMotorEx.class, "intake");
        orchestrator.registerNode("intake", new IntakeNode(orchestrator, intake));
        GamepadAdaptor.attach(orchestrator, gamepad1, "g1");
    }

    @Override protected void onSafeLoop() {
        telemetry.addData("power", orchestrator.getLatestValue("intake/power", Double.class).orElse(0.0));
        telemetry.update();
    }

    public static class IntakeNode extends Node {
        IntakeNode(Orchestrator orch, SafeDevice<DcMotorEx> intake) { super(orch); this.intake = intake; }
        private final SafeDevice<DcMotorEx> intake;
        private boolean running;

        @SubscribedTo(topic = "g1/right_bumper/rising")
        @OnHardwareThread
        public void onPress(Boolean v) {
            running = true;
            intake.run(m -> m.setPower(1.0));
        }

        @SubscribedTo(topic = "g1/right_bumper/falling")
        @OnHardwareThread
        public void onRelease(Boolean v) {
            running = false;
            intake.run(m -> m.setPower(0.0));
        }

        @RunPeriodically(hz = 10)
        public void publishState() {
            orchestrator.publish("intake/power", running ? 1.0 : 0.0);
        }
    }
}
```

## Why Synapse?

Pub/sub for FTC isn't new — [Heron Robotics](https://github.com/HeronRobotics/heron)
showed what it could do for an 18-ball autonomous, and their architecture
inspired this one. Synapse takes the same idea and makes it the **safest and
smallest** library in the niche:

- **One hardware thread, no exceptions.** Every `DcMotorEx`, `Servo`, and
  sensor I/O — whether triggered by a gamepad event, a periodic loop, or a
  bulk read — funnels through a single dedicated thread. A runtime assertion
  in `SafeOpMode.loop()` makes off-thread hardware access fail fast, not
  silently corrupt your I²C bus.
- **Three-layer API.** Annotations (`@SubscribedTo`, `@RunPeriodically`,
  `@RunnableAction`, `@OnHardwareThread`), a `HardwareActions` facade (`run`,
  `call`, `callAsync`, `bulkRead`), and `SafeDevice<T>` wrappers. Use
  whichever matches the call site.
- **Backpressure that respects deadlines.** Separate pools for periodic and
  callback work, with a bounded queue and `CallerRunsPolicy` so a slow
  subscriber slows the publisher instead of dropping messages. An unbounded
  action pool keeps one-shot invocations from being rejected under load.
- **Zero runtime dependencies.** ~40 KB JAR. R8 survival is verified by
  running the test suite through minification.
- **Gamepad-to-topic in one line.** `GamepadAdaptor.attach(orchestrator, gamepad1, "g1")`
  publishes buttons (current, rising, falling) and axes at 60 Hz.

## Why pub/sub for FTC?

Traditional command-based FTC code runs every subsystem on a single loop.
It works, but the only way to express "run my intake only when the bumper
state changes" and "update my LEDs at 10 Hz" in the same loop is to count
ticks manually and hope nothing else is dragging the loop down.

In a pub/sub architecture, those two requirements become two independent
subscriptions that the orchestrator schedules on separate pools. A slow
callback can't starve your periodic loop, and vice-versa — and any of them
can opt in to running on the dedicated hardware thread.

## Quickstart

```java
@TeleOp(name = "SynapseDemo", group = "Demo")
public class SynapseDemo extends SafeOpMode {

    @Override
    protected void onSafeInit() {
        SafeDevice<DcMotorEx> left  = safeMap.device(DcMotorEx.class, "leftMotor");
        SafeDevice<DcMotorEx> right = safeMap.device(DcMotorEx.class, "rightMotor");

        orchestrator.registerNode("drive", new DriveNode(orchestrator, left, right));
        GamepadAdaptor.attach(orchestrator, gamepad1, "g1");
    }

    @Override
    protected void onSafeLoop() {
        telemetry.update();
    }

    public static class DriveNode extends Node {
        DriveNode(Orchestrator orchestrator, SafeDevice<DcMotorEx> left, SafeDevice<DcMotorEx> right) {
            super(orchestrator);
            this.left = left;
            this.right = right;
        }
        private final SafeDevice<DcMotorEx> left, right;

        @RunPeriodically(hz = 50, hardware = true)
        public void drive() {
            double y = -orchestrator.getLatestValue("g1/left_stick_y", Float.class).map(Float::doubleValue).orElse(0.0);
            double r = -orchestrator.getLatestValue("g1/right_stick_y", Float.class).map(Float::doubleValue).orElse(0.0);
            left.run(m -> m.setPower(y));
            right.run(m -> m.setPower(r));
        }
    }
}
```

Full walkthrough in the
[First OpMode docs](https://github.com/IamCoder18/synapse/blob/main/website/src/content/docs/get-started/first-opmode.mdx);
a robot-validated drivetrain in the
[Mecanum drive recipe](https://github.com/IamCoder18/synapse/blob/main/website/src/content/docs/recipes/mecanum-drive.mdx).

## The one rule

> **All hardware access happens on the hardware thread. Nothing else.**

FTC hardware is not thread-safe. Synapse funnels every hardware operation
onto one OS thread so races cannot happen by construction, and gives you
three layers to route code there:

```java
// Layer 1 — annotations (the binder routes these for you)
@SubscribedTo(topic = "intake/set/power")
@OnHardwareThread
public void onTarget(double power) { intake.run(m -> m.setPower(power)); }

@RunPeriodically(hz = 50, hardware = true)
public void drive() { left.raw().setPower(powerFL); }   // already on the thread

// Layer 2 — the HardwareActions facade, from anywhere else
hardware.run(() -> motor.setPower(0.5));
double pos = hardware.call(() -> motor.getCurrentPosition());

// Layer 3 — SafeDevice wrappers
SafeDevice<DcMotorEx> intake = safeMap.device(DcMotorEx.class, "intake");
intake.run(m -> m.setPower(0.5));
```

And the mirror rule: **code already on the hardware thread must never block
waiting for it.** Calling `hardware.call(...)` / `device.call(...)` from
inside a `hardware = true` loop, an `@OnHardwareThread` subscriber, or a
bulk-read reader deadlocks the robot — use `device.raw()` there.

## Concepts

### Topics

A topic is a named, typed channel. Create explicitly with
`orchestrator.getOrCreateTopic(name, type)` (recommended for anything shared —
it pins the type in one place) or implicitly on first publish. Read back with
`orchestrator.getLatestValue(name, type)`, which returns an `Optional`.

Types are checked with assignability, and primitives and wrappers are
normalized — a `double` parameter binds cleanly to a `Double` topic.

### Subscribing

Three ways, pick per call site:

```java
// 1) Programmatic — returns a Subscription you can unsubscribe later.
orchestrator.subscribe("intake/set/power", Double.class,
        p -> intake.run(m -> m.setPower(p)));

// 2) Annotation — declared on a Node method, wired at registerNode.
@SubscribedTo(topic = "intake/set/power")
public void onSetPower(double power) { target = power; }

// 3) Fetch the latest value on demand — no subscription needed.
Optional<Double> latest = orchestrator.getLatestValue("intake/set/power", Double.class);
```

### Periodic loops

```java
@RunPeriodically(hz = 50)                    // scheduler pool (8 threads)
public void update() { /* math */ }

@RunPeriodically(hz = 50, hardware = true)   // the hardware thread
public void drive() { motor.raw().setPower(...); }
```

Fixed-delay scheduling: the next run starts `1000 / hz` ms after the previous
*finishes*, so slow loops never overlap and the effective rate is `hz` or
lower. Exceptions are logged; the loop keeps running.

### Hardware-thread API

```java
HardwareActions hw = orchestrator.hardware();

hw.run(() -> motor.setPower(0.5));                      // async
double pos = hw.call(() -> motor.getCurrentPosition()); // blocks caller
CompletableFuture<Double> f = hw.callAsync(() -> motor.getCurrent(CurrentUnit.AMPS));

hw.bulkRead(50, view -> {
    double amps = motor.getCurrent(CurrentUnit.AMPS);   // on the hardware thread
    view.publish("motor/amps", amps);
});
```

### Gamepad

`GamepadAdaptor.attach(orchestrator, gamepad1, "g1")` pre-creates and publishes
at 60 Hz:

| Topic                  | Type    | Meaning                              |
| ---------------------- | ------- | ------------------------------------ |
| `g1/<button>`          | Boolean | current state, every poll            |
| `g1/<button>/rising`   | Boolean | fires (value=true) on 0→1 transition |
| `g1/<button>/falling`  | Boolean | fires on 1→0 transition              |
| `g1/<axis>`            | Float   | current value                        |

Every public non-static `boolean`/`float` field of the SDK `Gamepad` is picked
up automatically — 27 buttons and 10 axes on SDK 11.2, including touchpad and
`*_trigger_pressed` fields — and future SDK fields appear with no code change.

## Threading model

The orchestrator runs four distinct workers, each chosen for a specific role:

| Pool              | Threads         | Bounded? | Used for                                      |
| ----------------- | --------------- | -------- | --------------------------------------------- |
| **Scheduler**     | 8               | —        | `@RunPeriodically` loops (non-hardware)       |
| **Callback**      | 4–16            | 256      | `@SubscribedTo` handlers (with backpressure)  |
| **Action**        | unbounded       | no       | `@RunnableAction` invocations                 |
| **Hardware**      | 1 (dedicated)   | —        | All hardware reads/writes                     |

A slow subscriber can't starve a periodic loop — they run on separate pools.
When the callback queue fills, `CallerRunsPolicy` runs the overflow on the
publisher's thread (backpressure instead of dropped messages). Exceptions in
subscribers, loops, and actions are logged and never crash the OpMode.

## Installation

Synapse is published to **Maven Central**, which needs no authentication —
the standard `FtcRobotController` template already includes `mavenCentral()`
in its repositories, so adding the dependency is all it takes:

```gradle
dependencies {
    implementation 'com.aaravlabs:synapse:0.4.0'
    // ... your other FTC deps
}
```

### Alternative: GitHub Packages mirror

Synapse is also mirrored on **GitHub Packages**
(`maven.pkg.github.com/IamCoder18/synapse`) — useful if your organization
already authenticates against GitHub. That mirror requires a GitHub token
with the `read:packages` scope even for public packages (a GitHub
restriction, not a Synapse one). If in doubt, use Maven Central above.

Add the mirror repository and the dependency to `build.dependencies.gradle`:

```gradle
repositories {
    mavenCentral()
    google()
    maven {
        url = uri("https://maven.pkg.github.com/IamCoder18/synapse")
        credentials {
            username = findProperty("githubUser") ?: System.getenv("GITHUB_USER") ?: System.getenv("GITHUB_ACTOR")
            password = findProperty("githubToken") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'com.aaravlabs:synapse:0.4.0'
    // ... your other FTC deps
}
```

and configure credentials in `~/.gradle/gradle.properties` (never commit
this file):

```properties
githubUser=<your-github-username>
githubToken=<a-token-with-read:packages>
```

Full walkthrough with the Kotlin DSL and troubleshooting in the
[Install docs](https://github.com/IamCoder18/synapse/blob/main/website/src/content/docs/get-started/install.mdx),
also served at `/docs/get-started/install` on the website.

## API reference

| Type | Where | Purpose |
| --- | --- | --- |
| `Orchestrator` | `com.aaravlabs.synapse` | The bus. One per robot. |
| `Node` | `com.aaravlabs.synapse` | An independent unit of code with annotated callbacks. |
| `Topic<T>` | `com.aaravlabs.synapse` | A named, typed channel. |
| `Subscription` | `com.aaravlabs.synapse` | Handle returned by `subscribe()`. |
| `LogSink` | `com.aaravlabs.synapse` | Pluggable logging output. |
| `@SubscribedTo` | `com.aaravlabs.synapse.annotation` | Callback on every published value. |
| `@RunPeriodically` | `com.aaravlabs.synapse.annotation` | Loop at a fixed frequency. |
| `@RunnableAction` | `com.aaravlabs.synapse.annotation` | One-shot, named, fire-on-demand. |
| `@OnHardwareThread` | `com.aaravlabs.synapse.annotation` | Forces a `@SubscribedTo` onto the hardware thread. |
| `SafeOpMode` | `com.aaravlabs.synapse.ftc` | Drop-in `OpMode` base class. |
| `FtcOrchestrator` | `com.aaravlabs.synapse.ftc` | Factory that wires `android.util.Log` into a `LogSink`. |
| `HardwareActions` | `com.aaravlabs.synapse.ftc` | `run` / `call` / `callAsync` / `bulkRead`. |
| `SafeDevice<T>` | `com.aaravlabs.synapse.ftc` | Hardware wrapper that routes through the hardware thread. |
| `SafeHardwareMap` | `com.aaravlabs.synapse.ftc` | `HardwareMap` that produces `SafeDevice<T>`s. |
| `GamepadAdaptor` | `com.aaravlabs.synapse.ftc` | Reflects `Gamepad` fields into topics. |
| `BulkReader` | `com.aaravlabs.synapse.ftc` | Functional interface for `bulkRead`. |
| `HardwareView` | `com.aaravlabs.synapse.ftc` | Publish/read surface inside a bulk-read callback. |
| `AndroidLogSink` | `com.aaravlabs.synapse.ftc` | `LogSink` forwarding to `android.util.Log`. |

## Testing & development

The library ships with 51 JUnit 5 tests covering topics, subscriptions,
periodic loops, two-pool isolation, hardware-thread serial execution, soak
tests, race conditions, and the real-FTC-SDK `Gamepad` field set.

```bash
./gradlew test                       # run the test suite
./gradlew javadoc                    # build the Javadoc
./gradlew publishToMavenLocal        # install into ~/.m2 for experimentation
```

Requirements: JDK 11+, Gradle 9.x.

A real working TeleOp that exercises the library on a competition robot
(mecanum drive + intake subsystems) is in
[ATAARobotics/23684-Canopy-Biobuzz PR #3](https://github.com/ATAARobotics/23684-Canopy-Biobuzz/pull/3).

## Contributing

Issues and PRs welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md) for the
workflow, [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) for the code of
conduct, and [CHANGELOG.md](./CHANGELOG.md) for the version history.

## License

[MIT](./LICENSE)

## Credits

Created and maintained by [IamCoder18](https://github.com/IamCoder18).
