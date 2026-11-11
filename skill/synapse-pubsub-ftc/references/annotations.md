# Synapse annotations reference

Canonical reference for the four annotations. All live in `com.aaravlabs.synapse.annotation` (sources under `src/main/java/com/aaravlabs/synapse/annotation/`). Binding is done by `AnnotationBinder` at `registerNode` time, scanning the node's class and all superclasses.

## @SubscribedTo

```java
@SubscribedTo(topic = "intake/set/power")
@OnHardwareThread
public void onTarget(double power) { intake.run(m -> m.setPower(power)); }
```

| Aspect | Contract |
| --- | --- |
| Attribute | `topic()` — required `String`. **No shorthand**: `@SubscribedTo("x")` does not compile. |
| Repeatable | Yes (container `@SubscribedTos`). One method may subscribe to several topics. |
| Parameters | 0 or 1. With one, the type must match the topic's type or be a supertype. Primitives ≡ wrappers (`double` binds to a `Double` topic). |
| Dispatch | Callback pool (4–16 threads). With `@OnHardwareThread`, re-routed to the hardware thread. |
| Type mismatch | Message not matching the parameter type is **silently dropped** — the handler never runs, nothing is logged. |
| Errors | Exceptions are logged via the orchestrator sink; other subscribers continue. |
| Cleanup | Unsubscribed automatically on `unregisterNode` / `close()`. |
| Binding errors | ≥ 2 parameters → `IllegalArgumentException` at `registerNode`. Static methods are rejected. |

Runtime path: binder calls `subscribeRaw(topic, paramType, reflectiveHandler)`; the topic is created (typed by the parameter) if absent; if `@OnHardwareThread` is present the binder wraps the handler to submit onto the hardware thread instead of the callback pool.

Gotchas:
- Subscribing to `g1/a` (base topic) fires every poll (60 Hz) while held. Presses usually want `g1/a/rising`.
- A typo'd topic name is not an error — you just subscribe to a topic nobody publishes to. Grep both sides when a handler "doesn't run".

## @RunPeriodically

```java
@RunPeriodically(hz = 50, hardware = true, labels = {"write"})
public void drive() { /* touches motors */ }
```

| Aspect | Contract |
| --- | --- |
| Attributes | `hz()` — int, default 20, must be > 0. `hardware()` — boolean, default `false`. `labels()` — `String[]`, default `{}` — gate labels for hardware ticks. |
| Repeatable | Yes (container `RunPeriodicallys`). |
| Parameters | None. Return ignored. Static methods rejected with `IllegalArgumentException` at `registerNode`. |
| Scheduling | `scheduleWithFixedDelay`, delay = `max(1, 1000/hz)` ms between **completions**. Effective rate ≤ `hz`. First invocation immediate. |
| Pool | Scheduler (8 threads) or the single hardware thread with `hardware = true`. |
| Gate labels | While a restriction covers any of `labels()`, the tick is **skipped** — DROP and QUEUE alike; ticks are never held or queued (a tick is stale the moment it is late) — and the loop resumes on its normal schedule on release. **No slots**: periodic work has labels only. `labels` without `hardware = true` → WARN + ignored. Malformed/reserved labels → `IllegalArgumentException` at `registerNode`. |
| Errors | `hz <= 0` logs a warning and skips the loop. Body exceptions are logged; the loop keeps running. |
| Cleanup | Future tracked per node; cancelled on unregister/close. |

Gotchas:
- Forgetting `hardware = true` on a device-touching loop is the most common migration bug — the loop runs on the scheduler pool where hardware access is forbidden.
- A loop body slower than `1000/hz` ms simply runs slower; it never overlaps itself.
- A covered labelled tick skips cleanly — nothing accumulates during the hold and nothing replays at release.

## @RunnableAction

```java
@RunnableAction("shooter/launch")
public void launch() { orchestrator.publish("shooter/set/speed", 4800.0); }
```

| Aspect | Contract |
| --- | --- |
| Attribute | `value()` — the action name. `@RunnableAction("fire")` shorthand is valid. |
| Repeatable | No. |
| Parameters | 0. Must return `void`. Must not be static — violations throw `IllegalArgumentException` at `registerNode`. |
| Pool | Dedicated unbounded pool (`SynchronousQueue`, grows on demand) — **not** the hardware thread. |
| Firing | `orchestrator.runAction(name)` → `CompletableFuture<Void>`; completes on normal return; completes exceptionally if the method throws, the name is unknown, or the pool is shut down. |
| Name collisions | Global registry. First wins; second logs a warning and is skipped. Prefix names with the subsystem. |
| Lifecycle | `cancelAllActions()` shuts the pool down **permanently** for the OpMode — all later `runAction` calls fail. |

Hardware inside an action must be routed through `hardware.run(...)`/`device.run(...)` — the action pool is not the hardware thread, and `@OnHardwareThread` has no effect on actions.

## @OnHardwareThread

```java
@SubscribedTo(topic = "intake/set/power")
@OnHardwareThread
public void onTarget(double power) { ... }
```

| Aspect | Contract |
| --- | --- |
| Attributes | `labels()` — `String[]`, default `{}` (gate labels). `slot()` — `String`, default `""` (coalescing slot). Both optional — bare `@OnHardwareThread` still works. |
| Applies to | Only meaningful combined with `@SubscribedTo`. No effect on `@RunPeriodically` methods (use `hardware = true`) or `@RunnableAction`. |
| Dispatch | The binder wraps the subscription handler so it submits onto the single dedicated hardware thread (`synapse-<name>-hw-1`). Each dispatch is one gate submission carrying `labels()`/`slot()`. |
| Guarantees | Strictly serial with all other hardware-thread work; never overlaps. |
| Labels / slot | While a gate restriction covers any label, dispatches are dropped or held per the restriction's `GateMode` (dropped ones are counted; one summary WARN at release). With a slot, at most one undelivered dispatch exists — a newer message supersedes the older (`Reason.SUPERSEDED`), so a flooded setpoint topic collapses to its latest value. Malformed/reserved labels throw `IllegalArgumentException` at `registerNode`. Unlabelled is the default and stays first-class (reachable only via `HardwareGate.ALL` / `HardwareGate.UNLABELLED`). |

```java
@SubscribedTo(topic = "drive/set/power")
@OnHardwareThread(labels = {"write"}, slot = "drive-power")
public void onPower(double p) { motor.setPower(p); }   // gate-labelled, coalesced setpoint
```

**What it does NOT do:**

- Does not intercept hardware calls in unannotated code — a raw `motor.setPower(...)` in a plain subscriber still runs on the callback pool.
- Does not protect `onSafeLoop()`; `SafeOpMode.loop()` asserts its own thread separately.
- Does not apply to `@RunnableAction` methods.

**Deadlock:** code already on the hardware thread must not call `hardware.call(...)`/`device.call(...)` (blocks on itself). Use `device.raw()` there.
