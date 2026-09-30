# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Added

- `PublishListener`: a pluggable hook notified synchronously on every
  `Orchestrator.publish`, before subscriber dispatch. Intended for diagnostics --
  recording, metrics, tracing -- which previously had no way to observe the bus
  without reimplementing `publish`.

  Registered with `addPublishListener` / `removePublishListener`, declared as
  `default` methods so existing `Orchestrator` implementors and test doubles keep
  compiling. Listeners run on the publishing thread, must not block, and are
  called in registration order. A listener that throws is caught and logged, so
  diagnostics can never break the bus. With no listeners registered, `publish`
  costs a single volatile read.

  `addPublishListener` throws `UnsupportedOperationException` on an
  implementation that does not support listeners, rather than accepting the
  registration and quietly recording nothing; `removePublishListener` is always
  a safe no-op. Implementors that can support listeners must override both.

  Listeners are notified before the topic's type is validated, so a publish
  rejected with `IllegalArgumentException` is still reported. One publish
  iterates a snapshot of the listener list taken when it starts, so a listener
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

[Unreleased]: https://github.com/IamCoder18/synapse/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/IamCoder18/synapse/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/IamCoder18/synapse/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/IamCoder18/synapse/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/IamCoder18/synapse/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/IamCoder18/synapse/releases/tag/v0.2.0
