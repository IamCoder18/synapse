# Synapse API surface (v0.5.0)

Exact public surface. Anything not listed (notably `com.aaravlabs.synapse.internal.*`) is internal and may change without notice.

## com.aaravlabs.synapse

### Orchestrator (interface, AutoCloseable)

```java
String name()
<T> Topic<T> getOrCreateTopic(String name, Class<T> type)   // throws IAE on type conflict; primitives ≡ wrappers
Optional<Topic<?>> findTopic(String name)
<T> Optional<Topic<T>> findTopic(String name, Class<T> type) // empty if absent or incompatible
<T> void publish(String name, T value)                       // lazy topic creation; null value → IAE; non-blocking
<T> Subscription subscribe(String name, Class<T> type, Consumer<? super T> handler)  // callback pool
<T> Optional<T> getLatestValue(String name, Class<T> type)   // empty if unpublished/incompatible — never throws
Node registerNode(String name, Node node)                    // binds annotations; duplicate name → no-op + WARN, returns prior
void unregisterNode(String name)                             // cancels loops, unsubscribes, removes actions, node.close()
Optional<Node> findNode(String name)
CompletableFuture<Void> runAction(String actionName)         // failed future if unknown/closed/pool shut down
void cancelAllActions()                                      // PERMANENTLY shuts the action pool
ScheduledFuture<?> runPeriodically(Runnable task, int hz)    // programmatic, scheduler pool; hz must be > 0
void runOnHardwareThread(Runnable task)                      // ignored after close
HardwareActions hardware()
void log(String message) / log(String tag, String message)
void warn(String message) / error(String message) / error(String message, Throwable t)
boolean isClosed()
void close()                                                 // idempotent; unregisters nodes (close() hooks), shuts all pools
static Orchestrator create(String name)                      // stderr sink
static Orchestrator create(String name, LogSink sink)
```

### Node (abstract)

```java
protected Node(Orchestrator orchestrator)   // subclasses MUST call super(orch)
protected final Orchestrator orchestrator
void close()                                // default no-op; called on unregister/close
```

### Topic<T>

```java
String name()
Class<T> type()
Optional<T> latestValue()
T latestValueOr(T defaultValue)
long latestPublishNanos()                   // System.nanoTime() clock
```

### Subscription

```java
void unsubscribe()                          // idempotent
```

### LogSink (interface)

```java
void info(String tag, String message)
void warn(String tag, String message)
void error(String tag, String message)
void error(String tag, String message, Throwable t)
LogSink STDERR                              // System.err — used by Orchestrator.create(name)
LogSink SILENT                              // drops everything — useful in tests
```

## com.aaravlabs.synapse.annotation

```java
@SubscribedTo        { String topic(); }                      // repeatable (SubscribedTos); no shorthand
@RunPeriodically     { int hz() default 20; boolean hardware() default false;
                       String[] labels() default {}; }        // repeatable (RunPeriodicallys); labels gate hardware ticks only
@RunnableAction      { String value(); }                      // not repeatable
@OnHardwareThread    { String[] labels() default {}; String slot() default ""; }  // marker for @SubscribedTo methods
```

Binding-time rejections (`IllegalArgumentException` at `registerNode`): ≥2 params on `@SubscribedTo`; any params or `static` on `@RunPeriodically`/`@RunnableAction`; non-`void` `@RunnableAction`; malformed or reserved gate `labels` on `@OnHardwareThread`/`@RunPeriodically`. (`labels` on `@RunPeriodically` without `hardware = true` → WARN + ignored.)

## com.aaravlabs.synapse.ftc

### SafeOpMode (abstract, extends OpMode)

```java
protected Orchestrator orchestrator          // created in init(), closed in stop()
@Deprecated protected Orchestrator orch      // alias, kept compiling
protected HardwareActions hardware
protected SafeHardwareMap safeMap            // wraps hardwareMap

protected abstract void onSafeInit()         // required
protected void onSafeInitLoop()              // init_loop(), default no-op
protected void onSafeStart()                 // start(), default no-op
protected void onSafeLoop()                  // loop(), default no-op; runs after assert + tick
protected void onSafeStop()                  // stop(), BEFORE orchestrator.close()
```

### FtcOrchestrator (static factory)

```java
static Orchestrator create()                 // name "ftc", AndroidLogSink
static Orchestrator create(LogSink sink)
```

### HardwareActions (final)

```java
void run(Runnable action)                                    // async
void run(String label, Runnable action)                      // labelled sugar
<T> T call(Callable<T> action) throws Exception              // BLOCKS caller; DEADLOCKS from hardware thread
<T> T call(Callable<T> action, long timeout, TimeUnit unit)  // + TimeoutException
<T> T call(String label, Callable<T> action) throws Exception // labelled; FAILS FAST under a gate hold
<T> T call(String label, Callable<T> action, long timeout, TimeUnit unit)
<T> CompletableFuture<T> callAsync(Callable<T> action)       // non-blocking; safe anywhere
<T> CompletableFuture<T> callAsync(String label, Callable<T> action)
Scope labels(String... labels)                               // scope submissions by gate labels
Scope slot(String slot)                                      // scope submissions by coalescing slot
BulkReadHandle bulkRead(int hz, BulkReader reader)           // periodic on hardware thread; hz must be > 0
void stopBulkRead(BulkReadHandle handle)                     // null-safe cancel
boolean restrict(String label, GateMode mode)                // gate: covers label + all sublabels
boolean restrict(String label, GateMode mode, ChildPolicy childPolicy)
HardwareGate.RestrictionBuilder restrict(String label)       // fluent form
boolean release(String label)                                // exact node; one summary WARN of drops/skips
boolean isRestricted(String label)
boolean isHardwareThread()
void assertNotHardwareThread()                               // throws IllegalStateException on hardware thread
void tick()                                                  // reserved no-op; SafeOpMode.loop() calls it
void shutdown()                                              // closes the ENTIRE orchestrator
static final class BulkReadHandle { void cancel(); }
static final class Scope {                                   // immutable; each configurator returns a NEW scope
    Scope labels(String... labels)                           // replaces this scope's labels
    Scope slot(String slot)                                  // replaces this scope's slot
    void run(Runnable action)
    <T> T call(Callable<T> action) throws Exception
    <T> T call(Callable<T> action, long timeout, TimeUnit unit)
    <T> CompletableFuture<T> callAsync(Callable<T> action)
    BulkReadHandle bulkRead(int hz, BulkReader reader)       // IAE if the scope has a slot — no slots on periodic work
}
```

`hardware.run(r)` is unlabelled work — fine forever; `hardware.run("write", r)` is single-label sugar; `hardware.labels("write", "climb").slot("claw").run(r)` is the general form (`hardware.slot("claw")` scopes by slot alone). Labels/slots ride every hardware-thread submission, including `@OnHardwareThread` dispatches and `hardware = true` ticks (labels only). Labels: any number, optional; reserved `HardwareGate.ALL`/`UNLABELLED` → `IllegalArgumentException`. Slots: optional opaque non-empty strings.

### HardwareGate (final)

The gate over the hardware thread: labels, restrictions, slots. Gate ops are mirrored on `HardwareActions` — call them as `hardware.restrict(...)` etc.

```java
static final String ALL                                // "~all" — covers EVERY hardware action (incl. unlabelled)
static final String UNLABELLED                         // "~unlabelled" — exactly the label-less actions
boolean restrict(String label, GateMode mode)          // covers label + all sublabels (downward only)
boolean restrict(String label, GateMode mode, ChildPolicy childPolicy)
RestrictionBuilder restrict(String label)              // fluent; .drop() or .queue() REQUIRED before .apply()
boolean release(String label)                          // exact node only; logs one summary WARN of drops/skips
boolean isRestricted(String label)                     // would an action labelled `label` be covered right now?
void close()                                           // rejects held work; called by Orchestrator.close()

// RestrictionBuilder (single use):
RestrictionBuilder drop() / queue()                    // mode — exactly one required
RestrictionBuilder sealChildren()                      // = ChildPolicy.SEALED
RestrictionBuilder except(String... labels)            // carve labels+sublabels out of THIS restriction only
boolean apply()                                        // false + WARN if refused
```

Labels are `/`-separated paths — the path structure **is** the hierarchy (`write/drive` is a sublabel of `write`). Tilde escapes (JSON-Pointer style): `~1` = literal `/`, `~0` = literal `~`; decoded left-to-right, so `~01` is the literal `~1`. Matching is by decoded segments: `write` never catches `writex`; `write~1part` is ONE segment literally `write/part`, **not** under `write`. Malformed labels (`write~`, `write~2`, `a//b`, `/a`, `a/`, empty) throw `IllegalArgumentException` at submission/binding, but gate ops only refuse (`false` + WARN). `ALL`/`UNLABELLED` are reserved — they gate work, they never label it.

Restrictions cover the named label and all its sublabels (never upward). DROP discards covered work; QUEUE holds it until uncovered, then runs it in submission order. An action with several labels is covered if **any** label is; the strictest covering restriction wins (DROP > QUEUE > free). Held work is re-evaluated on every gate change — a later DROP drops held work retroactively. `release(label)` removes the restriction at exactly that node; re-restricting a label replaces the whole spec. `except(label)` must lie inside the covered subtree (else `apply()` refuses) and carves out only its own restriction — other restrictions still apply; under a seal an excepted subtree is also independently gateable. `sealChildren()`: this restriction alone decides for its covered subtree — `restrict`/`release` on covered descendants refuse (`false` + WARN, never throw), and pre-existing child restrictions are suspended (kept, restored at seal lift, incl. retro-drops).

Slots: at most one **pending** (not-yet-started) action per slot exists anywhere — held or queued behind busy hardware. A newer action with the same slot supersedes the older (`Reason.SUPERSEDED`); only the newest runs, so a slotted stream can never pile up. Periodic work (`bulkRead` readers, `@RunPeriodically(hardware = true)` ticks) has labels but **no slots**: a covered tick is skipped under either mode and the loop resumes on release. `Scope.bulkRead` throws `IllegalArgumentException` if the scope has a slot.

Internal-ish (reached via `HardwareActions`/annotations, not called directly): `submit`, `tickAllowed`, `validateLabels`, and `enum Fate { DISPATCHED, HELD, DROPPED }` (the fate of a submission at gate time).

### GateMode (enum)

```java
enum GateMode { DROP, QUEUE }
```
DROP — covered work never reaches the thread (fire-and-forget discarded + counted; `callAsync` future fails with `DROPPED_BY_GATE`; blocking `call` throws). QUEUE — covered work is held and runs when uncovered, in submission order; blocking `call` still fails fast and never waits. Periodic ticks are skipped under either mode.

### ChildPolicy (enum)

```java
enum ChildPolicy { INDEPENDENT, SEALED }
```
INDEPENDENT (default) — children may be restricted/released freely while this restriction is active. SEALED — this restriction alone decides below it (see `HardwareGate`).

### HardwareActionRejectedException (checked)

```java
class HardwareActionRejectedException extends Exception
enum Reason { DROPPED_BY_GATE, SUPERSEDED }   // SUPERSEDED = normal slot coalescing, not an error
Reason reason()
```
Headline rule: **an action runs, or its caller finds out why it didn't — and nothing crashes.** Delivered through the caller's channel: `run(...)` drops are counted and summarised in one WARN at `release`; `callAsync(...)` futures complete exceptionally with this exception; blocking `call(...)` throws it immediately (fail fast — never waits behind a QUEUE hold); gate ops never throw.

### SafeDevice<T> (final)

```java
void run(Consumer<? super T> action)                          // async
<R> R call(Function<? super T, ? extends R> action) throws Exception  // blocks; DEADLOCKS from hardware thread
<R> CompletableFuture<R> callAsync(Function<? super T, ? extends R> action)
Scope<T> labels(String... labels)                             // device-level mirror of HardwareActions.labels
Scope<T> slot(String slot)                                    // device-level mirror of HardwareActions.slot
T raw()                                                       // hardware-thread code ONLY
// Scope<T>: labels(...) / slot(...) configurators + run / call / callAsync with the scope's labels+slot
```

`device.labels("write").slot("intake-power").run(m -> m.setPower(p));` — the device mirror of the scoped `HardwareActions` form.

### SafeHardwareMap (final)

```java
<T> SafeDevice<T> device(Class<T> deviceClass, String name)
HardwareMap raw()
```

### GamepadAdaptor (final, extends Node)

```java
static String attach(Orchestrator orch, Object gamepad, String parentTopic)
// registers node "GamepadAdaptor:<parentTopic>", returns that name
// publishes at 60 Hz on the scheduler pool:
//   <parent>/<button>           Boolean  current state, every poll
//   <parent>/<button>/rising    Boolean  true on 0→1 transition only
//   <parent>/<button>/falling   Boolean  true on 1→0 transition only
//   <parent>/<axis>             Float    current value
// All topics pre-created at attach time. Every public non-static boolean/float
// Gamepad field is discovered reflectively (27 buttons, 10 axes on SDK 11.2);
// non-boolean/float fields (id, timestamp, nextRumbleApproxFinishTime) are skipped.
static final int HZ = 60;
```

### BulkReader (@FunctionalInterface)

```java
void read(HardwareView view)    // runs on the hardware thread
```

### HardwareView (final)

```java
<T> void publish(String topic, T value)
<T> Optional<T> getLatestValue(String topic, Class<T> type)
```

### AndroidLogSink (final, implements LogSink)

Forwards to `android.util.Log` via reflection; silent no-op on desktop JVMs.

## Error reference

| Condition | Behavior |
| --- | --- |
| `publish` value `null` | `IllegalArgumentException` |
| `publish` type incompatible with existing topic | `IllegalArgumentException` on the publishing thread |
| `getOrCreateTopic` type conflict | `IllegalArgumentException` |
| `registerNode` structural violation (param counts, static, non-void action) | `IllegalArgumentException` |
| `registerNode` duplicate name | No-op + WARN, returns prior node |
| `runAction` unknown name / closed / pool shut down | Failed future (`IllegalArgumentException` / `IllegalStateException`) |
| `hz <= 0` (`@RunPeriodically` or `runPeriodically`) | Warn + skip (annotation) / `IllegalArgumentException` (programmatic) |
| `assertNotHardwareThread` on hardware thread | `IllegalStateException` |
| `hardware.call` from hardware thread | Permanent deadlock — no exception, no log |
| Gate covers a `run(...)` submission | Dropped (DROP) or held-then-run (QUEUE); drops counted, one summary WARN at `release` |
| Gate covers a `callAsync(...)` submission | Future completes exceptionally with `HardwareActionRejectedException` |
| Gate covers a blocking `call(...)` | Throws `HardwareActionRejectedException` immediately — never waits behind a QUEUE hold |
| Newer submission with the same slot | Older one rejected `SUPERSEDED` before it starts (future/exception); only the newest runs |
| Malformed or reserved label at submission / `registerNode` | `IllegalArgumentException` |
| Malformed label, sealed ancestor, or nothing restricted in `restrict`/`release`/`apply` | `false` + WARN — gate ops never throw |
| Covered tick (`bulkRead` / `@RunPeriodically(hardware)`) | Skipped under either mode, counted, resumes on release |
| Publish/`runOnHardwareThread` after `close()` | Ignored + WARN |
| Subscriber/loop/action throws | Logged; execution continues |
