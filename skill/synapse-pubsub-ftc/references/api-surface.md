# Synapse API surface (v0.4.1)

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
@RunPeriodically     { int hz() default 20; boolean hardware() default false; }  // repeatable (RunPeriodicallys)
@RunnableAction      { String value(); }                      // not repeatable
@OnHardwareThread    {}                                       // marker for @SubscribedTo methods
```

Binding-time rejections (`IllegalArgumentException` at `registerNode`): ≥2 params on `@SubscribedTo`; any params or `static` on `@RunPeriodically`/`@RunnableAction`; non-`void` `@RunnableAction`.

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
<T> T call(Callable<T> action) throws Exception              // BLOCKS caller; DEADLOCKS from hardware thread
<T> T call(Callable<T> action, long timeout, TimeUnit unit)  // + TimeoutException
<T> CompletableFuture<T> callAsync(Callable<T> action)       // non-blocking; safe anywhere
BulkReadHandle bulkRead(int hz, BulkReader reader)           // periodic on hardware thread; hz must be > 0
void stopBulkRead(BulkReadHandle handle)                     // null-safe cancel
boolean isHardwareThread()
void assertNotHardwareThread()                               // throws IllegalStateException on hardware thread
void tick()                                                  // reserved no-op; SafeOpMode.loop() calls it
void shutdown()                                              // closes the ENTIRE orchestrator
static final class BulkReadHandle { void cancel(); }
```

### SafeDevice<T> (final)

```java
void run(Consumer<? super T> action)                          // async
<R> R call(Function<? super T, ? extends R> action) throws Exception  // blocks; DEADLOCKS from hardware thread
<R> CompletableFuture<R> callAsync(Function<? super T, ? extends R> action)
T raw()                                                       // hardware-thread code ONLY
```

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
| Publish/`runOnHardwareThread` after `close()` | Ignored + WARN |
| Subscriber/loop/action throws | Logged; execution continues |
