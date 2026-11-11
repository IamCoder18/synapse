# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Added

- **Hardware-thread labels, gate restrictions, and slots: every piece of
  scheduled hardware work can now be named, held back, or collapsed to its
  newest form before it reaches the thread.** Every hardware-thread submission
  -- `HardwareActions.run/call/callAsync/bulkRead`, `SafeDevice` calls,
  `@OnHardwareThread` dispatches, and `@RunPeriodically(hardware = true)` ticks
  -- may carry any number of optional **labels** and one optional **slot**, and
  passes through a `HardwareGate` that decides whether it may run.

  Labels are `/`-separated paths and the path structure is the hierarchy:
  `write/drive` is a sublabel of `write`, `restrict("write", ...)` covers it,
  and coverage travels downward only -- a restriction on `write/drive` never
  holds `write`, `writex` is never caught by `write`, and matching compares
  decoded segments, never raw string prefixes. Literal `/` and `~` inside a
  segment are escaped JSON-Pointer style (`~1`, `~0`), decoded left to right so
  `~01` is the literal `~1`; `write~1part` is therefore a single segment named
  `write/part` and deliberately *not* under `write`. Malformed labels
  (`write~`, `write~2`, `a//b`, `/a`, `a/`, empty) throw
  `IllegalArgumentException` at submission, at annotation binding (so a bad
  label fails at `registerNode`, not at first dispatch), and are refused with
  `false` plus a warning by gate operations. Two reserved labels anchor the
  tree: `HardwareGate.ALL` covers every hardware action -- including work with
  no labels at all -- and `HardwareGate.UNLABELLED` covers exactly the
  label-less work, which no other label can reach. Labelling is always
  optional: unlabelled, unslotted submissions are first-class and behave
  exactly as before.

  `restrict(label, GateMode.DROP)` discards covered work; `restrict(label,
  GateMode.QUEUE)` holds it and runs it in submission order once nothing covers
  it any more. An action carrying several labels is covered when *any* of them
  is covered, and the strictest covering restriction decides (DROP beats QUEUE
  beats running). Held actions are re-evaluated on every gate change: a
  restriction that starts dropping later drops still-held work at that moment
  (DROP means never, regardless of who got there first), and work runs only
  once every one of its labels is free. `release(label)` removes the
  restriction at exactly that node and logs one summary warning of what was
  dropped or skipped while it was restricted -- per-restriction counters make
  a 50 Hz stream visible without a 50 Hz wall of log spam.

  The fluent form adds policy on top: `hardware.restrict("shooter").drop()
  .sealChildren().except("shooter/write").apply()`. A `ChildPolicy.SEALED`
  restriction decides alone for its covered subtree -- `restrict`/`release` on
  covered descendants are refused (`false` plus a warning; gate operations
  never throw, so a misbehaving node cannot end a match) and pre-existing child
  restrictions are suspended, kept rather than deleted, and re-applied the
  moment the seal lifts. `except(label)` carves that label's subtree out of the
  restriction -- `except("shooter/write")` also uncovers `shooter/write/power`
  -- but only out of *that* restriction: another restriction still applies to
  the uncovered labels, and an excepted subtree sits outside the seal too, so
  it stays independently gateable. Exceptions must name a node inside the
  covered subtree, or the whole restriction is refused. Re-restricting a label
  replaces its entire spec (mode, child policy, exceptions); the builder
  refuses to apply without an explicit `.drop()` or `.queue()`, because the
  mode is a safety decision worth writing down.

  A **slot** is an opaque coalescing key: at most one *pending* action per slot
  exists anywhere -- held by a gate or merely queued behind busy hardware work
  -- and a newer action supersedes the older one unless it has already started,
  so only the most recent action per slot is ever used. A 50 Hz setpoint
  stream, or a flooded setpoint topic on a slotted `@OnHardwareThread`
  subscriber, collapses to a single latest write instead of piling up, and a
  long hold cannot grow the queue. Slots never apply to periodic work, where
  they would be meaningless: a labelled hardware tick or bulk-read tick is
  *skipped* while covered (under DROP and QUEUE alike -- a tick is stale the
  moment it is late) and the loop resumes on its normal schedule at release.

  The error story is one rule: **an action runs, or its caller finds out why it
  didn't -- and nothing crashes.** Fire-and-forget `run` is counted and
  summarised at release. `callAsync` completes its future exceptionally with
  `HardwareActionRejectedException` carrying a `Reason` (`DROPPED_BY_GATE` or
  `SUPERSEDED` -- the latter is not an error condition, it is what slot
  coalescing means). Blocking `call` fails fast with the same checked exception
  rather than ever waiting behind a QUEUE hold -- `call` already declares
  `throws Exception`, so this is source-compatible and the compiler forces
  every caller to handle it. An unobserved failed future is inert; the robot
  does not stop because a write was held back.

  `SafeDevice` mirrors the surface (`device.labels("write")
  .slot("intake-power").run(...)`), the annotations carry the same tokens
  (`@OnHardwareThread(labels = "write", slot = "drive-power")`,
  `@RunPeriodically(hardware = true, labels = "write")`), and `HardwareActions`
  gains `restrict` / `release` / `isRestricted` plus labelled overloads and an
  immutable scoped view (`hw.labels(...).slot(...)`). The motivating case --
  preventing every hardware WRITE until the match timer safely starts as
  decided by a button -- is three lines of gate and one subscriber, and the
  website's gating guide walks through it and through freezing a subsystem for
  the duration of a `@RunnableAction`.

### Fixed

- **`HardwareActions.call` / `callAsync` on a closed orchestrator now fail
  immediately instead of hanging.** Previously the submission was silently
  ignored and the caller's future never completed, so a blocking `call` waited
  forever. Both now report the closed orchestrator through the same channel as
  a gate rejection (`call` throws, `callAsync` fails its future).

## [0.4.1] - 2026-10-12

### Fixed

- **Zero-parameter `@SubscribedTo` methods no longer throw
  `IllegalArgumentException` when the topic was pre-created with a narrower
  type.** The binder used to request the topic as `Object` for trigger-style
  handlers, so registering one on e.g. `GamepadAdaptor`'s `Boolean` edge
  topics (`g2/b/falling`) failed at `registerNode` with "already exists with
  type java.lang.Boolean, cannot re-create as java.lang.Object". Trigger
  handlers ignore the published value, so they now attach via the new
  `OrchestratorImpl.subscribeTrigger`, which reuses the existing topic's type
  and only creates the topic as `Object` when it is absent (safe for later
  typed use, since every type is a subtype of `Object`). Typed handlers keep
  the previous `subscribeRaw` path, including the silent-drop guard and
  `@OnHardwareThread` rerouting. Regression tests cover trigger-on-typed-topic,
  typed-plus-trigger sharing one topic, and trigger-first ordering.

### Added

- `PublishListener`: a pluggable hook notified synchronously on every
  `Orchestrator.publish` that reaches the bus, before subscriber dispatch.
  Intended for diagnostics -- recording, metrics, tracing -- which previously
  had no way to observe the bus without reimplementing `publish`.

  Registered with `addPublishListener` / `removePublishListener`, declared as
  `default` methods so existing `Orchestrator` implementors and test doubles keep
  compiling. Listeners run on the publishing thread, must not block, and are
  called in registration order. A listener that throws is caught and logged, so
  diagnostics can never break the bus -- including when it throws an `Error`
  such as `AssertionError` or `NoClassDefFoundError`. The one exception is
  `OutOfMemoryError`, and only that: heap exhaustion is the one condition where
  the recovery path needs memory too, since logging the error allocates. Every
  other `VirtualMachineError` is contained. `StackOverflowError` is routinely
  recoverable (an unbounded listener recursion unwinds that listener's frames
and leaves the stack whole, with the heap untouched). `InternalError` and
`UnknownError` are both documented as serious VM failures, but no subclass of
either marks the fatal instance, so `publish` cannot tell a fatal one from a
benign one and does not guess -- the hierarchy's silence is not read as
evidence that they are harmless. What it does settle is the family: neither is
an `OutOfMemoryError`, so no instance of either reaches the one rethrow. That
is read across every module in the boot layer rather than `java.base` alone,
because `catch (OutOfMemoryError)` matches subclasses from any module --
`UnknownError` has no subclass there at all, and the sole `InternalError`
subclass is `java.util.zip.ZipError`, named as a fact about the family rather
than a hazard: the JDK documents it as no longer used and superseded by
`ZipException`, so a corrupt archive raises something else today. The bus is
not compromised in any of these cases, so the fault stays contained. With no
listeners registered, `publish` costs a single volatile read.

  `addPublishListener` throws `UnsupportedOperationException` on an
  implementation that does not support listeners, rather than accepting the
  registration and quietly recording nothing; `removePublishListener` is always
  a safe no-op. Implementors that can support listeners must override both.

  Listeners are notified before the topic's type is validated, so a publish
  rejected for a type mismatch is still reported. The two cases where a call
  never reaches the hook are documented rather than reported: a publish to a
  closed orchestrator returns early, and a publish of a `null` value throws
  `IllegalArgumentException` before any listener runs -- in both, nothing was
  published, and in the `null` case there is no value to hand a listener, which
  is why `onPublish` documents its value as never null. One publish iterates a
  snapshot of the listener list taken when it starts, so a listener
  unregistered part-way through still sees that publish but not the next one.

### Changed

- **`Orchestrator.hardware()` now returns a shared instance.** The
  `HardwareActions` facade is constructed once per orchestrator instead of once
  per call, and the `HardwareView` handed to `bulkRead` callbacks is likewise
  shared across registrations rather than allocated per registration. Both are
  immutable single-reference views over the orchestrator, so this is not a
  behavioral change beyond object identity — it is now safe to capture the
  facade once and reuse it on hot paths.
- **`@SubscribedTo` dispatch no longer re-boxes the parameter type per message.**
  The primitive-to-wrapper normalisation is computed once at bind time instead
  of on every delivered message. Equivalent to the previous conditional check.
- **`Topic` latest-value reads no longer synchronize.** `latestValue()` and
  `latestPublishNanos()` are plain volatile reads. The latest value and its
  timestamp are published together as one immutable pair through a single volatile
  field, so a reader always sees a value and a timestamp from the same publish.
  `publish` is reachable from the OpMode loop, the hardware thread, and the callback
  pool, so two publishers really can overlap.

  The write path still takes the topic monitor, but only around the clock sample,
  one short-lived allocation and the store. Two separate volatile fields would let
  two publishers interleave between the value write and the timestamp write;
  sampling the clock outside mutual exclusion would let a preempted publisher
  install an older pair after a newer one. Both regressions were reproduced and
  fixed; keeping the monitor on the write path preserves the ordering the previous
  `synchronized` body provided.

  Each publish therefore allocates one small short-lived pair on the write path.
  The read path is unchanged in allocation terms: `latestValueOr()` allocates
  nothing, while `latestValue()` wraps its result in an `Optional` as it did
  before.

  The topic's declared type is now normalized to its wrapper class **once**, at
  construction, for the per-publish type check. `type()` still reports the type the
  topic was created with — only the internal comparison field is boxed.

  `latestPublishNanos()` still returns `0` before the first publish, unchanged. That
  `0` is a heuristic rather than a proof — `System.nanoTime()` is permitted to return
  `0`, so a genuine publish can carry it too. The resulting over-reported age rejects
  a fresh value rather than admitting a stale one, so the failure direction is safe.

  **Prefer `Topic.latest()` when you need both the value and its age.** It returns the
  two from one snapshot read, so they provably come from the same publish:

  ```java
  Optional<Topic.Latest<T>> snap = topic.latest();
  T v;
  long age;
  if (snap.isPresent()) {
      Topic.Latest<T> s = snap.get();
      v = s.value();
      age = s.ageNanos();
  } else {
      v = defaultValue;
      age = Long.MAX_VALUE;
  }
  ```

  Unwrap once rather than calling `map()` per field: each `map()` allocates its own
  `Optional` and boxes the `long`, which is avoidable garbage in a periodic loop.

  `latestPublishNanos()` and `latestValueOr()` are unchanged and still correct. Composing
  them takes two calls, which can straddle a publish and leave the pair describing two
  different publishes; if you do compose them, **read the timestamp first**:

  ```java
  long stamp = topic.latestPublishNanos();  // read the timestamp FIRST
  T v = topic.latestValueOr(null);         // then
  long age = stamp == 0L ? Long.MAX_VALUE : System.nanoTime() - stamp;
  ```

  Reading the value first and the timestamp second can pair an older value with a
  newer timestamp, so an age check on that pair passes even though the value is
  stale. Reading the timestamp first can only over-report the age, never under-report
  it.

## [0.4.0] - 2026-09-12

### Changed

- **Renamed local/parameter/field `orch` to `orchestrator` for naming
  consistency.** All bindings across the codebase (Java sources, tests, docs,
  examples) now use the canonical full word `orchestrator`. Internal
  parameters on `HardwareActions`, `GamepadAdaptor`, and `AnnotationBinder`
  are renamed; this is not a binary-incompatible change at the Java source
  level because parameter names are not part of the signature.

### Deprecated

- **`SafeOpMode.orch` retained as a deprecated alias.** Subclasses that still
  reference `this.orch` will compile with a deprecation warning and continue
  to receive the same `Orchestrator` instance (kept in sync inside `init()`).
  The alias is a passive reference — lifecycle flows through
  `orchestrator.close()` only.

## [0.3.1] - 2026-09-07

### Changed

- **Published metadata refresh.** POM `name` corrected to `Synapse` (was
  `AaravLabs Synapse`) and `developer` block now lists `IamCoder18` (was
  `AaravLabs` / `IamCoder18`). LICENSE copyright updated to
  `Copyright (c) 2026 IamCoder18`. No code changes; rebuild picks up the
  fresh metadata.
- **Prose cleanup.** Brand-name mentions removed from the Javadoc on
  `SafeOpMode`, the `CONTRIBUTING` intro, the `SECURITY` policy display
  text, and the `README` license footer. Sample placeholders in
  `gradle.properties`, `CONTRIBUTING.md`, and the README install snippet
  now use `<your-github-username>` instead of a hardcoded value.
- **Author credit.** Added a "Credits" section to the README and a
  "Maintainer" note at the top of `CONTRIBUTING.md`, both pointing at
  the project's owner.

## [0.3.0] - 2026-09-07

### Changed

- **Project renamed to Synapse.** The repository, Maven artifact, Java
  package, and brand are now `Synapse` (formerly `AaravLabs PubSub` /
  `com.aaravlabs:pubsub` / `com.aaravlabs.pubsub.*`). The GitHub repository
  has been renamed in place; the local folder name is unchanged.
  - Maven coordinates: `com.aaravlabs:pubsub` → `com.aaravlabs:synapse`
  - Java base package: `com.aaravlabs.pubsub` → `com.aaravlabs.synapse`
    (subpackages `.annotation`, `.ftc`, `.internal` are unchanged in name)
  - ProGuard rules file renamed: `META-INF/proguard/pubsub.pro` →
    `META-INF/proguard/synapse.pro`
  - Hardware thread name prefix: `pubsub-` → `synapse-`
  - README rewritten with a "Why Synapse?" overview, a hardware-threading
    primer, a threading-model table, and a full API reference

## [0.2.1] - 2026-09-06

### Changed

- Repackaged with the FTC SDK reference classes moved to `compileOnly` stubs at
  `libs/ftc-sdk-stub.jar` so the published JAR no longer conflicts with the real
  RobotCore at the user's compile time.

## [0.2.0] - 2026-09-06

### Added

- `@SubscribedTo`, `@RunPeriodically`, `@RunnableAction`, and `@OnHardwareThread`
  annotations.
- `SafeOpMode` base class extending `com.qualcomm.robotcore.eventloop.opmode.OpMode`,
  with built-in thread assertion and orchestrator lifecycle.
- `HardwareActions` facade with `run`, `call`, `callAsync`, and `bulkRead`
  methods that route work to the dedicated hardware thread.
- `SafeDevice<T>` generic wrapper around any hardware object.
- `SafeHardwareMap` thin wrapper around the FTC SDK's `HardwareMap`.
- `GamepadAdaptor` reflection-based gamepad-to-topic publisher.
- Two-pool executor model: separate scheduled and callback thread pools with
  blocking-queue backpressure; an unbounded action pool for `@RunnableAction`.
- Dedicated single-threaded hardware executor for all hardware-touching code.
- Cross-thread type normalization (`double` ↔ `Double`) so annotation-bound
  primitive parameters and programmatically-typed topics interoperate.
- `isAssignableFrom`-based publish type checks (so an `Object`-typed topic
  accepts any value).
- Auto-creation of topics on first publish (a-la Heron's behavior).
- JUnit 5 test suite of ~53 tests covering topics, subscriptions, periodic
  loops, two-pool isolation, hardware-thread serial execution, soak tests,
  race conditions, and the real-FTC-SDK `Gamepad` field set.

[Unreleased]: https://github.com/IamCoder18/synapse/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/IamCoder18/synapse/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/IamCoder18/synapse/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/IamCoder18/synapse/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/IamCoder18/synapse/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/IamCoder18/synapse/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/IamCoder18/synapse/releases/tag/v0.2.0
