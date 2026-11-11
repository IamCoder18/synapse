# Synapse safety invariants

The guarantees Synapse provides around FTC hardware access. Every claim is scoped to **Synapse-routed** operations — raw hardware calls from code that bypasses the orchestrator are outside all of these invariants.

## 1. Single-threaded hardware execution

**Claim.** Every piece of hardware-touching code routed through Synapse — `@OnHardwareThread` subscribers, `@RunPeriodically(hardware = true)` loops, `HardwareActions.run/call/callAsync/bulkRead`, and `SafeDevice.run/call/callAsync` — executes on the same single OS thread, strictly serially. Labelled work may additionally be skipped or dropped by the gate (see invariant 6) before it reaches the thread.

**Where.** `OrchestratorImpl` creates the hardware worker via `Executors.newSingleThreadScheduledExecutor(hwTf)`; the thread is captured at construction and compared by identity in `isHardwareThread()`.

**The mirror rule (deadlock).** `HardwareActions.call(...)` and `SafeDevice.call(...)` submit to the hardware thread and then **block the caller** on the future. From code already on the hardware thread — a `hardware = true` loop, an `@OnHardwareThread` subscriber, a bulk-read reader — the single thread waits on a task it can never start: **guaranteed permanent hang**, no exception, no log line. Inside hardware-thread code, use `SafeDevice.raw()` (direct access, same thread) or `callAsync(...)` (non-blocking).

**Test.** `src/test/java/com/aaravlabs/synapse/HardwareThreadTest.java` — serial execution, ordering under load, `assertNotHardwareThread()` behavior.

## 2. Two-pool isolation

**Claim.** `@RunPeriodically` loops run on the scheduler pool; `@SubscribedTo` callbacks run on the callback pool. A slow subscriber cannot starve a periodic loop and vice-versa.

**Where.** `OrchestratorImpl` constructor: scheduler = `Executors.newScheduledThreadPool(8)`; callbacks = `ThreadPoolExecutor(4, 16, 60s, LinkedBlockingQueue(256), CallerRunsPolicy)`.

**Caveat (backpressure).** With the queue at 256 and all 16 threads busy, `CallerRunsPolicy` runs the overflow callback on the **publisher's** thread. A `hardware = true` loop publishing into a saturated bus can therefore execute a slow non-hardware subscriber on the hardware thread, temporarily eroding isolation. This is a deliberate trade: backpressure instead of dropped messages. Keep subscribers cheap; keep heavy work in `hardware = true` loops.

**Test.** `src/test/java/com/aaravlabs/synapse/ThreadingTest.java`.

## 3. Copy-on-write subscriber snapshots

**Claim.** Publishers iterate a stable snapshot of each topic's subscriber list; subscribers can join/leave mid-dispatch without `ConcurrentModificationException`.

**Where.** `OrchestratorImpl.SubscriberList` — copy-on-write array under a lock; `publish()` iterates `snapshot()` without holding the lock.

**Test.** `src/test/java/com/aaravlabs/synapse/SoakTest.java` — concurrent publishers/subscribers churn.

## 4. Fail-fast thread assertion in SafeOpMode

**Claim.** If `SafeOpMode.loop()` ever executes on the hardware thread, it throws `IllegalStateException` immediately instead of silently corrupting the bus.

**Where.** `SafeOpMode.loop()` calls `hardware.assertNotHardwareThread()` (which throws if `isHardwareThread()`) before `onSafeLoop()`.

**Test.** `src/test/java/com/aaravlabs/synapse/ftc/HardwareActionsTest.java` — the `isHardwareThread` / `assertNotHardwareThread` paths.

## 5. Error isolation

**Claim.** Exceptions thrown inside subscribers, periodic loops, or actions are logged through the orchestrator's `LogSink` and never crash the OpMode or kill the schedule.

**Where.** Every dispatch wrapper (`dispatchCallback`, `schedulePeriodic`, `scheduleHardwarePeriodic`, `scheduleHardwareBulkRead`, the action runner) catches `Throwable`, logs, and returns. Note: the annotation binder's wrapper rethrows after logging, but the orchestrator's outer catch still prevents schedule death.

## 6. The gate guarantee — an action runs, or its caller finds out why it didn't

**Claim.** Every hardware-thread submission — `HardwareActions.run/call/callAsync/bulkRead`, `SafeDevice.run/call/callAsync`, `@OnHardwareThread` dispatches, `@RunPeriodically(hardware = true)` ticks — either runs, or its caller finds out why it didn't — **and nothing crashes**. Futures always complete (result or `HardwareActionRejectedException`); blocking `call` fails fast with the checked `HardwareActionRejectedException` rather than waiting behind a `QUEUE` hold; fire-and-forget `run` work is counted and summarised in one warning per `release`; gate operations (`restrict`/`release`/`apply`) return `boolean` and warn — never throw; and held work is re-evaluated on every gate change, so `DROP` means never (work held under `QUEUE` is dropped retroactively if a later `DROP` restriction covers it).

**Where.** `HardwareGate` — `submit`/`resolveFate` at submission time, `reevaluate` on every restrict/release, per-restriction drop/skip counters summarised in `release` — and `HardwareActions` — `callBlocking` (fail fast on non-dispatch) and `callAsync0` (exceptional future completion).

**Test.** `src/test/java/com/aaravlabs/synapse/ftc/HardwareGateTest.java`.

## Thread inventory

| Thread | Name pattern | Role |
| --- | --- | --- |
| OpMode loop | FTC SDK thread | lifecycle hooks, telemetry |
| Scheduler pool | `synapse-<name>-1..8` | non-hardware periodic loops |
| Callback pool | `synapse-<name>-N` (grows 4→16) | non-hardware subscribers |
| Action pool | `synapse-<name>-N` (per invocation) | actions |
| Hardware thread | `synapse-<name>-hw-1` (exactly one) | all hardware work |

All are daemon threads, so they never block JVM exit; the orchestrator still shuts them down explicitly in `close()` (awaiting up to 100 ms each). When diagnosing a hang, dump the stack of `synapse-*-hw-1` first — a `call()` from the hardware thread shows up as that thread parked on a `CompletableFuture.get()`.

## What is out of scope

- **Raw device calls** from `onSafeLoop()`, plain callbacks, or actions bypass every invariant above. `assertNotHardwareThread()` only proves the *OpMode loop itself* is not on the hardware thread — it cannot detect or intercept ad-hoc writes. Migration greps exist for this reason (`references/migration.md` Step 1).
- **`hardware.shutdown()`** closes the entire orchestrator (all pools, all nodes), despite its name. Do not call it mid-OpMode.
- **`tick()`** is a reserved no-op hook called from `SafeOpMode.loop()`.
