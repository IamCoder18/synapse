# Plan: Objective comparative benchmarks — Synapse vs raw FTC SDK vs SolversLib

## Problem

Synapse has no way to objectively benchmark itself against idiomatic raw FTC SDK code
or against a command-based framework (user decision: **SolversLib** `org.solverslib:core`
— the maintained FTCLib fork — not unmaintained FTCLib). Existing "timing" in the test
suite (`SoakTest`, `HardwareActionsTest`) is correctness-oriented. The website plan
(`.kilo/plans/1788803586700-website-and-skill-plan.md`) explicitly forbids invented
numbers, so today there are zero measured claims anywhere.

Goal: a deterministic, repeatable, CI-runnable benchmark suite that AI agents and humans
can run with one command to (a) compare the three styles across a complexity ladder from
tiny programs (raw FTC wins) to heavy multi-subsystem robots with expensive vision and
PIDF loops (Synapse wins), and (b) regress/optimize Synapse internals against a committed
baseline.

## Hard requirement: bench the frameworks, not mocks

**Measured code paths must execute the real, unmodified framework classes:**

| Style | Real code that must be on the measured path |
| --- | --- |
| Synapse | `OrchestratorImpl.publish/subscribe/getOrCreateTopic`, `Topic.recordLatest`, `SubscriberList` dispatch, `AnnotationBinder` reflective invoke for `@SubscribedTo`/`@RunPeriodically`, `HardwareActions.run/call` on the real hardware executor, real `GamepadAdaptor.poll()` |
| SolversLib | `com.seattlesolvers.solverslib.command.CommandScheduler.run()` from the published AAR, real `Subsystem.periodic()`, `Command.initialize/execute/isFinished/end`, real `GamepadEx.readButtons()` / `ButtonReader` / `GamepadButton` via `CommandScheduler.addButton`, real `setDefaultCommand` |
| Raw FTC | Literal `OpMode` lifecycle (`init()`/`loop()` on the stub class) with direct field reads and direct device writes — no harness loop abstraction |

**Fakes are allowed only at the physical I/O boundary** (there is no robot on a desktop JVM):

- `SimMotor`/`SimServo`/`SimEncoder` replace `DcMotorEx`/`Servo` — plain field writes into a
  shared `SimPlant`. Constant-cost, allocation-free.
- **The `Gamepad` object is the real stub class** (`com.qualcomm.robotcore.hardware.Gamepad`
  with its real volatile field set from `libs/ftc-sdk-stub.jar`). The stimulus thread flips
  those fields; `GamepadAdaptor` and `GamepadEx` read them for real. No gamepad double.
- `SimCamera` produces frames (timestamped buffers); the *processing* is a shared expensive
  kernel (`SyntheticVisionPipeline`), identical in all styles.
- `SimPlant` (1 kHz physics integrator thread) is shared "world" code — it is not part of any
  framework and must never sit inside a measured dispatch segment except as the device write
  performed by framework-invoked code.

**Prohibited:**

- Any local reimplementation of a scheduler, bus, subscription list, command loop, or button
  edge detector in the `solverslib`/`synapse`/`raw` style packages. Style packages may only
  *use* framework APIs and the shared sim kernel.
- Recording timestamps in harness glue instead of inside the code the framework invoked.
  Actuation timestamps are written by `SimMotor.setPower` called **from** a `@SubscribedTo`
  handler / `Command.execute()` / `loop()` body.
- Wrapping `publish()` in a measured helper that bypasses `OrchestratorImpl` dispatch.

**Verification gates (automated, run as part of the suite):**

1. `:benchmarks:verifyFrameworkClasses` — runtime class-identity assertions in each style's
   bootstrap: `CommandScheduler` is loaded from the extracted `org.solverslib:core` AAR
   `classes.jar` (`getProtectionDomain().getCodeSource()`), `OrchestratorImpl` from the
   `project(':')` output, stub `Gamepad` from `ftc-sdk-stub.jar`. Fails the run if a class
   resolves from `benchmarks/build` or an unexpected jar.
2. `:benchmarks:verifyMockBudget` — `micro.sim.deviceWrite` quantifies `SimMotor` write cost
   and asserts it is < 5% of the smallest framework-level measurement, so mock noise cannot
   dominate or mask framework overhead.
3. Structural review rule (documented in `benchmarks/README.md`): `benchmarks/src/main/java/.../shared`
   contains zero `com.aaravlabs.synapse.*` and zero `com.seattlesolvers.solverslib.*` dispatch
   types; style packages contain zero classes named like `*Scheduler`, `*Orchestrator`, `*Bus`.

Synthetic SDK stubs (`com.qualcomm.hardware.lynx.LynxModule` etc.) exist only so SolversLib
classes can *link* on a desktop JVM (the checked-in `ftc-sdk-stub.jar` lacks `LynxModule`,
which `CommandScheduler` references in `setBulkReading`/`run`). They are never called on a
measured path (`setBulkReading` is not used; hardware reads go through `SimPlant`).

## Design principles

1. **Identical work, idiomatic code.** Each scenario is the same workload (same physics, same
   vision kernel, same PIDF math, same stimulus timeline) hand-written three times in each
   style's natural idiom. Shared code is only the world/sim, never the dispatch.
2. **Ladder, not a single number.** S0 (raw FTC wins) → S3 (Synapse wins). Reporting must show
   the whole curve; cherry-picking is impossible by construction.
3. **Agent-first.** Deterministic seeds, `--quick` (~60 s) and `--full` modes, machine-readable
   JSON with stable field names, `compare` subcommand with tolerances and exit codes.
4. **Zero impact on the published artifact.** `benchmarks/` is a separate Gradle subproject;
   root `build.gradle` publishing/deps untouched. "No new dependencies" (`CONTRIBUTING.md`)
   applies to the library; benchmark-only deps stay in `:benchmarks`.
5. **Honest metrics.** Percentiles, not means. Environment metadata in every result. Known
   confounds documented next to the numbers.

## Scenario ladder

Shared world for every scenario: `SimPlant` (drive base + lift with gravity/friction + intake
roller), `SimCamera` (30 Hz frames into a ring buffer), seeded `StimulusTimeline` (gamepad
events + frame cadence, identical per style per scenario).

### S0 — `S0_MinimalDrive` (raw FTC is expected to win)
One stick → one motor power, one loop. No subscriptions, no commands, no nodes beyond the
minimum. Measures fixed overhead tax of each style on the smallest possible program.
Expected: raw ≪ SolversLib ≤ Synapse on actuation latency and loop Hz.

### S1 — `S1_BasicTeleop` (raw FTC likely still wins)
Tank drive (2 motors), 1 servo, intake toggle on bumper edges (rising/falling), telemetry
publish at 10 Hz. This is the common rookie TeleOp.
Expected: raw wins latency; gap quantifies "what does structure cost on a simple robot".

### S2 — `S2_MultiSubsystem` (crossover)
Drive + intake + lift (PIDF at 100 Hz) + outtake, two gamepads, mixed rates (drive 50 Hz,
lift PIDF 100 Hz, telemetry 10 Hz), one moderate "auto-align" computation (~0.5 ms) on a
gamepad event. Command conflicts (intake vs outtake) exercised via SolversLib requirements
and Synapse actions.
Expected: raw loop rate falls as work is serialized; per-task rates begin to slip for the
single-loop styles; Synapse keeps per-pool rates. Latency may still favor raw.

### S3 — `S3_HeavyRobot` (Synapse is expected to win)
Everything in S2 plus:
- **Expensive vision:** `SyntheticVisionPipeline` (~3 ms of real pixel work over a 320×240
  buffer) per camera frame at 30 Hz, result feeds an alignment controller.
- **Two PIDF loops** at 100–200 Hz (lift position + drivetrain heading hold) against
  `SimPlant`; tracking RMSE is a first-class metric.
- **Slow debug logger** (10–20 ms work per event) subscribed to state updates — the classic
  "one slow consumer" that stalls a single loop.
Expected: raw + SolversLib single loop degrade (PIDF rate collapse, tracking error growth,
latency p99 explosion); Synapse isolates vision/logger on the callback pool and keeps PIDF
near target on the hardware thread.

### Honesty variant — `rawmt` (S2/S3 only)
Raw FTC with hand-rolled threads (vision on its own thread, logger on its own thread), the
best a competent team writes without a framework. Included so the comparison cannot be
dismissed as "you forced vision into one loop". Expected to narrow the S3 gap substantially;
this is a feature of the results, not a problem.

## Styles (implementation sketch)

- `shared/` — `SimPlant`, `SimMotor`, `SimServo`, `SimCamera`, `SyntheticVisionPipeline`,
  `SharedPidf`, `StimulusTimeline`, `Probe`, `Hist` (percentile histogram), `Env`,
  `Report` (JSON + Markdown writers). No framework imports (gate 3).
- `raw/` — scenario classes structured as `init()` + `while (active) { loop(); }` on one
  thread, extending the stub `OpMode` so the lifecycle is real. Direct `gamepad.*` reads,
  direct `SimMotor` writes.
- `rawmt/` — same world, explicit `Thread`s for vision/logger as described above.
- `solverslib/` — idiomatic command-based: `Subsystem` subclasses with `periodic()`,
  `Command`/`InstantCommand`/`RunCommand` + `setDefaultCommand`, `GamepadEx` +
  `GamepadButton.whenPressed/whenReleased` registered through real `CommandScheduler.addButton`,
  one `CommandScheduler.getInstance().run()` pump loop (the idiomatic OpMode loop).
  PIDF via shared `SharedPidf` inside a subsystem (framework scheduling is what's under test,
  not controller math). Vision in `VisionSubsystem.periodic()` (idiomatic single-thread),
  with `rawmt`-style threaded variant only in the honesty variant notes.
- `synapse/` — idiomatic Synapse: `Node` subclasses with `@SubscribedTo`/`@RunPeriodically`
  (`hardware = true` for actuation/PIDF), `GamepadAdaptor.attach(...)`, `hardware().run/call`
  for device writes (real hardware-thread hop), camera results published to
  `camera/frame` from `SimCamera`'s capture thread and consumed by a `@SubscribedTo` handler
  (real callback-pool dispatch), `LogSink.SILENT` for quiet runs.

## Metrics (full suite, per scenario × style)

1. **Input→actuation latency** (ns): stimulus write of a `Gamepad` field (or frame-ready) →
   `SimMotor`/`SimServo` write executed by framework-invoked code. p50/p90/p99/max + count.
2. **Per-task achieved rate + jitter**: target Hz vs achieved Hz and period-jitter p99 for
   every periodic task (drive, PIDF loops, telemetry, vision, gamepad poll).
3. **Control quality under load**: lift position RMSE and heading RMSE vs setpoint trajectory
   (behavioral proof that a stalled loop is worse than an isolated one).
4. **Loop/scheduler throughput**: iterations/s of the raw loop / `CommandScheduler.run()` /
   Synapse scheduler ticks.
5. **Allocation rate** (bytes/s via `ThreadMXBean.getThreadAllocatedBytes`, optional flag).

Micro layer (Synapse optimization targets + comparators), each with warmup + N iterations +
volatile blackhole sink:

- `micro.raw.directCall` (floor)
- `micro.publish.subscribers{0,1,8}` programmatic handlers (real `OrchestratorImpl` dispatch)
- `micro.publish.annotationSubscriber` (real `AnnotationBinder` `Method.invoke`)
- `micro.topic.recordLatest` / `latestValue` (synchronized contention, 1P1C and 4P4C)
- `micro.subscribe.churn` (real `SubscriberList` add/remove/replace)
- `micro.hardware.run` / `micro.hardware.call` round-trip (real hardware executor)
- `micro.gamepad.adaptorPoll` (real `GamepadAdaptor.poll`)
- `micro.solverslib.schedulerRun{1,8}` subsystems (real `CommandScheduler.run`)
- `micro.solverslib.buttonRead` (real `GamepadEx.readButtons`)
- `micro.sim.deviceWrite` (mock-budget gate)

**Rejected: JMH.** The interesting paths are cross-thread dispatch (queue handoff, executor
round-trips, 60 Hz polling), which fits JMH's tight-invocation model poorly; a custom harness
with warmup rounds, percentile histograms, multi-round medians, and `--forks` (fresh JVM per
fork) keeps one runner, one report format, and zero plugin risk on Gradle 9. Methodology is
documented in `benchmarks/README.md` so the numbers are defensible.

## Gradle / dependency strategy

- `settings.gradle`: add `include 'benchmarks'` (root publishing untouched).
- `benchmarks/build.gradle`:
  - `implementation project(':')` — the real Synapse classes under test.
  - `compileOnly files('../libs/ftc-sdk-stub.jar')` + `testRuntimeOnly` equivalent — real stub
    `Gamepad`/`OpMode`.
  - SolversLib consumption (benchmark-only):
    ```gradle
    repositories { maven { url 'https://repo.dairy.foundation/releases' }; mavenCentral() }
    configurations { solverslibAar }
    dependencies {
      solverslibAar('org.solverslib:core:0.3.6@aar')
      runtimeOnly 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10' // only if linked classes need it
    }
    ```
    A `extractSolverslib` task unpacks `classes.jar` from the AAR into
    `benchmarks/build/solverslib/` and puts it on the compile/runtime classpath — the exact
    published artifact, no vendored sources.
  - `benchmarks/sdk-stubs/` source dir: synthetic `com.qualcomm.hardware.lynx.LynxModule`
    (+ nested `BulkCachingMode`) and any other link-only SDK types discovered while wiring
    SolversLib; never on a measured path.
  - Java 11 to match the library; runs on the CI JDK 17 the same way `test` does.
- CI (optional follow-up): `workflow_dispatch` job running `:benchmarks:run --args='--quick'`
  and uploading `results/latest.json` as an artifact. Not required for the core deliverable.

## Results & agent workflow

```
benchmarks/
  build.gradle
  README.md                     # methodology, fairness rules, mock policy, how to interpret
  sdk-stubs/                    # link-only synthetic SDK types
  src/main/java/com/aaravlabs/synapse/bench/
    shared/  raw/  rawmt/  solverslib/  synapse/
    harness/ (Hist, Probe, StimulusTimeline, Env, Report, Main)
  results/
    baseline.json               # committed reference run (from --full on a quiet machine)
    latest.json  latest.md      # outputs of the last run (gitignored except baseline)
```

CLI (single entrypoint `Main`, driven by `gradle :benchmarks:run --args='...'`):

- `run --quick|--full [--scenarios S0,S3] [--styles raw,solverslib,synapse] [--forks N]`
  → `results/latest.json` + `results/latest.md`
- `compare results/baseline.json results/latest.json [--tolerance 0.15]` → per-metric
  regression table on stdout; exit 0 = within tolerance, exit 1 = regression, exit 2 = missing
  metrics (so agents can branch on the outcome).
- JSON schema (stable, versioned `"schema": 1`): env block (OS, JDK, CPU model, git SHA,
  timestamp, mode, seed), then `scenarios[]` with `latencyActuationNs{p50,p90,p99,max,count}`,
  `taskRates{...}`, `trackingError{liftRmse,headingRmse}`, `loopHz`, `allocBytesPerSec`, and
  `micro{...}`.

Agent loop this enables: change Synapse source → `:test` → `:benchmarks:run --quick`
→ `compare` against baseline → keep/iterate. Determinism: fixed stimulus seeds, fixed
scenario durations (`--quick` ≈ 3 s measure per pair, `--full` ≈ 15 s), warmup window before
each measurement, median-of-rounds.

## Implementation order

1. Scaffold `:benchmarks` subproject + extract task + `sdk-stubs`; smoke test that real
   `OrchestratorImpl` and real SolversLib `CommandScheduler` both load and run a trivial
   round-trip (this flushes out missing SDK stubs early).
2. Harness core: `Hist`, `Probe`, `StimulusTimeline`, `Env`, `Report`, `Main` with
   `--quick/--full`, JSON+MD writers.
3. Shared world: `SimPlant`, devices, `SimCamera`, `SyntheticVisionPipeline`, `SharedPidf`.
4. S0 × {raw, solverslib, synapse} end-to-end + `verifyFrameworkClasses` + `verifyMockBudget`
   gates. Validate expected ordering (raw wins S0).
5. Micro layer (Synapse internals + SolversLib comparators + `micro.sim.deviceWrite`).
6. S1, S2 (add `rawmt` at S2).
7. S3 with vision + dual PIDF + slow logger. Validate expected Synapse win and `rawmt` honesty
   variant behavior.
8. `compare` subcommand + tolerances + exit codes; run `--full` twice on a quiet machine,
   record `results/baseline.json`, document variance observed.
9. `benchmarks/README.md` (methodology, mock policy, interpretation guide, agent recipe).

## Acceptance criteria

- `gradle test` still green (51 tests); root artifact/publishing unchanged (`git diff
  build.gradle` empty).
- One command produces `latest.json` + `latest.md` with all scenarios × styles in `--quick`.
- **Framework-realness gates pass** (class provenance from AAR/project jars; mock budget <5%).
- Ladder shape reproduced: S0 raw latency < synapse latency; S3 synapse lift-RMSE and PIDF
  achieved-Hz better than raw and solverslib; S0–S3 table shows the crossover rather than one
  style winning everywhere.
- `compare` exits 1 on an injected 2× slowdown of `OrchestratorImpl.publish` and 0 on a
  no-op run (self-test of the regression detector).
- Re-run stability: key p50s within ~15% across two consecutive `--full` runs on an idle
  machine (documented, with `--forks` available to tighten).

## Risks / mitigations

- **SolversLib AAR linkage** (needs `LynxModule`, possibly `HardwareMap.getAll`, Kotlin
  stdlib): discovered and fixed at step 1 smoke test via `sdk-stubs`; stubs never measured.
- **Fairness criticism** ("you strawmanned raw/SolversLib"): identical shared kernels, real
  framework dispatch, idiomatic style code, `rawmt` honesty variant, and a public
  methodology doc; S0 explicitly reports the case raw wins.
- **CI noise**: results always include env metadata; `compare` uses tolerances and medians of
  rounds; baseline regenerated per machine when needed.
- **Harness overhead polluting latency**: `Probe` is nanosecond stamps into preallocated
  rings; `verifyMockBudget` bounds its share.
- **Gradle 9 quirks with AAR extraction**: plain `Copy`/`unzip` task from a detached
  configuration — no Android plugin required.

## Out of scope (follow-ups)

- On-robot `BenchmarkOpMode` for Control Hub absolute numbers.
- Publishing measured numbers to the website (would be a deliberate policy change to the
  no-numbers ComparisonTable rule; real measured data would make it permissible).
- Benchmarks of Pedro Pathing/Photon modules or real EasyOpenCV pipelines.
- Nightly CI trend dashboards.
