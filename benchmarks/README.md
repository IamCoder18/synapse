# Synapse benchmark suite

Honest apples-to-apples robot-code benchmarks comparing three ways of writing FTC
control software on one shared simulated robot:

| style | what it is | what it may use |
| --- | --- | --- |
| `raw` | idiomatic FTC: one `OpMode.init()/loop()`, direct field reads and device writes | stub `OpMode`, `Gamepad` fields, `Telemetry` |
| `rawmt` | the best competent raw team: raw plus hand-rolled worker threads (S2/S3 only) | same as `raw`, plus plain `java.lang.Thread` |
| `solverslib` | idiomatic command-based FTCLib: `Subsystem.periodic()`, `Command`/`InstantCommand`/`RunCommand` + `setDefaultCommand`, `GamepadEx` + `GamepadButton.whenPressed/whenReleased` via `CommandScheduler.addButton`, one `CommandScheduler.run()` pump | published `org.solverslib:core` AAR |
| `synapse` | idiomatic Synapse: `OrchestratorImpl`, `Topic`, `Node`/`AnnotationBinder`, `GamepadAdaptor`, `HardwareActions`/`SafeDevice`, `@RunPeriodically`, `@SubscribedTo`, `@OnHardwareThread`, `RunnableAction` | this project's real dispatch |

Every style drives **the same** `SimPlant` (differential drive + lift + intake + servo)
through the same stub `Gamepad` fields and the same seeded `StimulusTimeline`, and is
scored on the same metrics. The ladder S0→S3 adds complexity to the *workload*, not
to the harness.

```
S0_MinimalDrive     one stick → one motor power, one loop
S1_BasicTeleop      tank drive, one servo, intake toggle on bumper edges, telemetry 10 Hz
S2_MultiSubsystem   + lift PIDF 100 Hz, outtake on gamepad 2, 0.5 ms auto-align on a
                    gamepad event, mixed rates (drive 50 Hz / lift 100 Hz / telem 10 Hz)
S3_HeavyRobot       + 30 Hz vision (~3 ms/frame), heading-hold PIDF 200 Hz, slow debug
                    logger (15 ms per 50 Hz state update)
```

## Running

```bash
gradle :benchmarks:run --args='run --quick'                     # ≈60 s ladder
gradle :benchmarks:run --args='run --full'                      # 15 s measure, 3 rounds (median)
gradle :benchmarks:run --args='run --full --forks 2'            # fresh-JVM repeats, merged median
gradle :benchmarks:run --args='run --full --forks 2 --rounds 1' # same, 1 round per fork (faster)
gradle :benchmarks:run --args='run --quick --scenarios S3 --styles raw,synapse'
gradle :benchmarks:run --args='run --quick --alloc'             # + thread alloc rate
gradle :benchmarks:run --args='compare results/baseline.json results/latest.json'
gradle :benchmarks:run --args='compare --self-test'
gradle :benchmarks:verifyFrameworkClasses                       # gates 1 + 3
gradle :benchmarks:verifyMockBudget                             # gate 2
```

Exit codes: `run` exits non-zero if a gate fails; `compare` exits `0` within
tolerance, `1` on a regression beyond tolerance, `2` when a metric is missing.

Outputs land in `benchmarks/results/latest.json` (schema-versioned) and
`results/latest.md` (human table). `results/baseline.json` is committed and is
machine-referenced — it is a *local* change-detector baseline, not a universal
truth. Treat every number here as a ratio on this box, never as an absolute.

## Metrics

| metric | how it is recorded |
| --- | --- |
| input→actuation latency | `LatencyProbe`: the stimulus thread stamps t0 immediately before flipping a `Gamepad` volatile field and declares the expected power direction (mapped per style sign convention); `SimMotor.setPower`/`SimServo.setPosition` stamp t1 **inside the framework-invoked device write** and the first write whose power crosses a threshold in that direction pairs with it (stick steps alternate sign, so stale writes cannot pair). Reported p50/p90/p99/max. |
| per-task rate + jitter | `TaskMeter.tick()` at the top of each periodic body the framework invokes (`loop()` body, `Command.execute()`/`Subsystem.periodic()`, `@RunPeriodically` method): achieved Hz vs target Hz and p99 inter-tick period. |
| control quality | `SimPlant` scores lift position and drivetrain heading against the same `Setpoints` trajectory plus `headingBias` the styles command — world-side bookkeeping, never inside a dispatch segment. |
| loop throughput | framework pump iterations/s: the `while (active) { loop(); }` body for `raw`/`rawmt`, one `CommandScheduler.run()` call for `solverslib`, and every framework-invoked handler body (`@RunPeriodically`, `@SubscribedTo`, `@RunnableAction`) for `synapse` — its "scheduler ticks", since it has no single pump |
| allocation rate | optional (`--alloc`): `com.sun.management.ThreadMXBean` bytes/s summed across all live threads (the measured style threads included). |
| micro layer | warmup + measured batches (local primitives) or per-op samples (cross-thread dispatch), volatile blackhole sink. |

Timestamps are always taken **inside the code the framework invokes**, never in
harness glue wrapped around a framework call — otherwise you would time the
harness, not the framework.

## Fairness rules

1. **Identical work.** Same physics, same stimulus timeline (seed 42, jittered
   cadence so events cannot phase-lock with fixed task periods), same controller
   math (`SharedPidf`), same expensive kernels (`SyntheticVisionPipeline`,
   `BusyWork`). Only dispatch wiring differs. Axis sign conventions differ by API
   (`GamepadEx.getLeftY()` negates, `getRightY()` does not — styles normalize to
   forward-positive so matched sticks drive matched wheels).
2. **Idiomatic code per style.** Each scenario×style is hand-written in the style's
   natural idiom (raw loop with elapsed-time rate gates; SolversLib subsystems +
   requirements-based command conflicts; Synapse nodes/annotations/topics/actions).
   There is no shared "robot program" abstraction.
3. **The world is not a framework.** `shared/` has zero Synapse/SolversLib dispatch
   types (gate 3). Device writes are constant-cost volatile stores into `SimPlant`.
4. **Real code on the measured path.** Synapse runs the real `OrchestratorImpl`
   dispatch, `AnnotationBinder` reflective invoke, `GamepadAdaptor.poll()`,
   `HardwareActions.run/call`, `Topic` synchronization. SolversLib runs the real
   published `CommandScheduler`/`GamepadEx` bytecode from
   `org.solverslib:core:0.3.6@aar` (extracted, never rebuilt). `raw`/`rawmt`
   execute a literal stub `OpMode` lifecycle.
5. **No double-counting.** Recording happens inside probed bodies; histograms are
   preallocated rings (constant-cost, allocation-free on the path).

## Why not JMH

The interesting paths are cross-thread dispatch (`publish()` → callback pool →
handler; `HardwareActions.run()` → hardware thread) and scheduling jitter over
millisecond-scale windows. JMH measures single-thread steady-state ops per second;
it cannot express "p99 of stimulus→actuation under a 30 Hz vision load", and its
sample patterns (batched single-thread loops) would hide exactly the queueing and
priority inversion we need to see. The micro layer here is a small custom harness:
warmup, per-op nanosecond samples for dispatch, batched samples for local
primitives, preallocated percentile histograms.

## Verification gates

Automated and enforced by `run`, `verifyFrameworkClasses`, `verifyMockBudget`:

1. **Class provenance.** At runtime, `CommandScheduler` must load from the
   extracted `org.solverslib:core` AAR `classes.jar`, `Gamepad` from the checked-in
   `libs/ftc-sdk-stub.jar`, `OrchestratorImpl`/`GamepadAdaptor` from the real
   project output — and **never** from `benchmarks/build/classes` (which would mean
   a vendored reimplementation). A smoke dispatch (Orchestrator + GamepadAdaptor +
   CommandScheduler.run()) must execute.
2. **Mock budget.** `micro.sim.deviceWrite` measures `SimMotor.setPower` *as used
   on the measured path* (volatile plant write + `System.nanoTime()` + the
   latency-probe pairing). It must cost **&lt; 5 % of the smallest framework-level
   dispatch measurement** — here the end-to-end paths the device write sits inside:
   `micro.publish.subscribers{1,8}`, `micro.publish.annotationSubscriber`,
   `micro.hardware.run`, `micro.hardware.call`. Local primitive micros
   (`recordLatest`, `schedulerRun`, `buttonRead`) are optimization targets and
   deliberately not budget references: they contain no dispatch hop for the mock
   to hide in. Current budget use ≈ 1 %.
3. **Structural review rule** (checked automatically and by reviewers):
   `shared/` contains zero `com.aaravlabs.synapse.*` / `com.seattlesolvers.solverslib.*`
   dispatch types, and style packages contain zero classes named like
   `*Scheduler`, `*Orchestrator`, `*Bus`. A team adding S4 must not quietly
   reimplement a bus.

## Interpreting the ladder

Expected **shape** (trends, not absolute truths):

* **S0/S1: raw wins latency** (µs) — one tight loop has no dispatch tax. SolversLib
  pays `CommandScheduler.run()` per iteration (≈2× raw, still µs). Synapse pays the
  `GamepadAdaptor` 60 Hz poll + callback + hardware-thread hops (ms).
* **S2: the crossover** — multi-rate work serialized into one loop starts to cost;
  per-pool rates hold for Synapse. Latencies converge on the 50 Hz drive period.
* **S3: Synapse wins control quality and p99** — the 15 ms debug logger and 3 ms
  vision serialize behind `raw`/`solverslib`'s single loop (PIDF achieved Hz halves,
  p99 actuation latency explodes). Synapse runs PIDF on the hardware thread and the
  slow consumers on the callback pool, so lift/heading RMSE and achieved rates win.
* **`rawmt` narrows the S3 gap** (honesty variant): hand-rolled threads give raw
  code the same isolation Synapse has — vision/logger on their own threads keep the
  control loop near target. What remains is dispatch architecture, not threading.
  A study where Synapse beats rawmt by the same margin it beats raw is lying.

If the shape is absent (e.g. Synapse wins S0 latency by 2×), suspect a harness bug
before concluding anything about the framework.

## Adding a scenario (S4 sketch: micro-autonomy)

S4 would add a fast pure-pursuit segment with 3 sensor inputs at different rates and
an end-of-match macro sequence (3-way command conflicts). Sketch of the workload —
**write it three times, in each style's idiom**:

* shared: `PurePursuitKernel` (identical math, preallocated buffers),
  `Setpoints.path()` waypoint stream, two extra 100 Hz/20 Hz sensor fakes at the
  I/O boundary writing into `SimPlant`-side state.
* `raw`: the controller in the single `loop()` with elapsed-time gates; the macro is
  a hand-rolled state machine.
* `solverslib`: `PurePursuitSubsystem.periodic()`, `FollowPathCommand` /
  `MacroCommand` with real requirements for the conflicts.
* `synapse`: `@SubscribedTo` sensor handlers + `@RunPeriodically(hardware = true)`
  controller, `RunnableAction` macro steps fired from button edges.

Then: extend `Scenario`, `Registry`, `StimulusTimeline.build`, add three pair
classes, and let gates 1–3 catch any accidental framework reimplementation.

## Known confounds

* Desktop JVM + simulated plant: absolute numbers are not robot-bus latencies.
* Vision and logger kernels are duration-targeted busy work (~3 ms / ~15 ms) so the
  load profile is machine-independent while wall-clock cost is real.
* The plant integrates at 1 kHz on its own thread and scores against wall-clock
  setpoints (the same ones the controllers read); thread hiccups appear in every
  style equally.
* `GamepadEx` axis sign conventions are asymmetric; styles normalize so matched
  sticks command matched wheels (see Fairness rule 1).
* One quick run has ~10 latency samples per pair (seeded stick steps in the 3 s
  window); `--full` and `--forks` widen the sample and take medians before you
  trust a &lt;10 % difference.
