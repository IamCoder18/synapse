package com.aaravlabs.synapse.ftc;

import com.aaravlabs.synapse.OrchestratorImpl;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A typed facade over the orchestrator's hardware thread. Provides four ways to
 * interact with FTC hardware safely:
 *
 * <ul>
 *   <li>{@link #run(Runnable)} — async, returns immediately.</li>
 *   <li>{@link #call(Callable)} — synchronous, blocks caller until the hardware
 *       thread completes the work and returns the value.</li>
 *   <li>{@link #callAsync(Callable)} — non-blocking, returns a
 *       {@link CompletableFuture}.</li>
 *   <li>{@link #bulkRead(int, BulkReader)} — periodic, runs the callback on the
 *       hardware thread at a fixed rate and lets it publish values to topics.</li>
 * </ul>
 *
 * <p>Every method on this class routes to the orchestrator's dedicated single
 * hardware thread. Code that touches {@code HardwareMap} devices should always
 * go through this facade.
 *
 * <h2>Labels, gates, and slots</h2>
 *
 * <p>Every submission may carry optional <b>labels</b> and an optional <b>slot</b>,
 * and every submission passes through the {@link HardwareGate gate}:
 *
 * <pre>{@code
 * hardware.run(r);                                        // unlabelled, no slot — fine, forever
 * hardware.run("write", r);                               // single-label sugar
 * hardware.labels("write", "climb").slot("claw").run(r);   // the general form
 *
 * hardware.restrict("write", GateMode.QUEUE);              // hold write/** until released
 * hardware.release("write");
 * }</pre>
 *
 * <p>Labels are {@code /}-separated paths whose structure is a hierarchy
 * ({@code write/drive} is a sublabel of {@code write}); a restriction covers the named
 * label and all its sublabels. {@link HardwareGate#ALL} restricts every hardware
 * action and {@link HardwareGate#UNLABELLED} the label-less ones — nothing else can
 * reach unlabelled work. Slots coalesce: at most one pending action per slot exists,
 * and only the most recent one is used. Labelling is always optional.
 *
 * <p>The fate of a labelled action under restriction — dropped, held and run on
 * release, or refused outright for blocking {@link #call} — is documented on
 * {@link GateMode}, {@link HardwareActionRejectedException}, and {@link HardwareGate}.
 *
 * <p><b>Never call {@link #call} (or {@link SafeDevice#call}) from code that is
 * already running on the hardware thread</b> — inside a
 * {@code @RunPeriodically(hardware = true)} loop, an {@code @OnHardwareThread}
 * subscriber, or a {@link #bulkRead} reader. {@code call} blocks until the
 * hardware thread finishes the submitted task, but the hardware thread is busy
 * running your code, so the task can never start and the robot hangs forever.
 * Code already on the hardware thread should use {@link SafeDevice#raw()} and
 * touch the device directly.
 *
 * <p>Obtain an instance via {@code orchestrator.hardware()}.
 */
public final class HardwareActions {

    private final OrchestratorImpl orchestrator;

    public HardwareActions(OrchestratorImpl orchestrator) {
        this.orchestrator = orchestrator;
    }

    // ------------------------------------------------------------------
    // async — fire-and-forget
    // ------------------------------------------------------------------

    /**
     * Schedule {@code action} to run on the hardware thread. Returns immediately;
     * the action runs in the future on the dedicated hardware thread.
     *
     * @param action the runnable to execute on the hardware thread
     */
    public void run(Runnable action) {
        submit(null, null, action, null, true);
    }

    /**
     * {@link #run(Runnable)} with a gate label. While any restriction covers
     * {@code label}, the action is dropped or held according to the restriction's
     * {@link GateMode}; see {@link HardwareGate}.
     *
     * @param label the gate label (must be a well-formed label path)
     * @param action the runnable to execute on the hardware thread
     * @throws IllegalArgumentException if the label is malformed or reserved
     */
    public void run(String label, Runnable action) {
        submit(new String[] {label}, null, action, null, true);
    }

    // ------------------------------------------------------------------
    // sync — block caller until the hardware thread completes the work
    // ------------------------------------------------------------------

    /**
     * Run {@code action} on the hardware thread, blocking the caller until it
     * completes. Returns the value the callable produced.
     *
     * <p>Useful when you need to read a hardware value from the OpMode loop
     * without blocking the hardware thread from doing other work — the caller
     * waits, but the hardware thread can still service other queued work in
     * parallel (sequentially, on its single thread).
     *
     * <p>If the call is covered by a gate restriction it <b>fails fast</b> with a
     * {@link HardwareActionRejectedException} rather than waiting behind a hold —
     * {@code call} never queues. Use {@link #callAsync} for work that may wait.
     *
     * <p><b>Deadlock warning:</b> calling this from code that already runs on the
     * hardware thread (a {@code hardware = true} loop, an {@code @OnHardwareThread}
     * subscriber, or a {@link #bulkRead} reader) blocks the hardware thread on
     * itself and hangs the robot. Use {@link SafeDevice#raw()} there instead.
     *
     * @param action the callable to execute on the hardware thread
     * @param <T> the result type
     * @return the value the callable produced
     * @throws HardwareActionRejectedException if a gate restriction covered the call
     *         (including a slot supersede) before it ran
     * @throws Exception whatever the callable threw (unwrapped from the future)
     */
    public <T> T call(Callable<T> action) throws Exception {
        return callBlocking(null, null, action, 0, null);
    }

    /**
     * Same as {@link #call(Callable)} but with an explicit timeout.
     *
     * @param action the callable to execute on the hardware thread
     * @param timeout maximum time to wait for the result
     * @param unit the timeout unit
     * @param <T> the result type
     * @return the value the callable produced
     * @throws TimeoutException if the hardware thread did not finish in time
     * @throws Exception whatever the callable threw (unwrapped from the future)
     */
    public <T> T call(Callable<T> action, long timeout, TimeUnit unit) throws Exception {
        return callBlocking(null, null, action, timeout, unit);
    }

    /**
     * {@link #call(Callable)} with a gate label. Fails fast while the label is
     * restricted — blocking calls never queue behind a gate hold.
     *
     * @param label the gate label
     * @param action the callable to execute on the hardware thread
     * @param <T> the result type
     * @return the value the callable produced
     * @throws IllegalArgumentException if the label is malformed or reserved
     * @throws Exception whatever the callable threw, or a
     *         {@link HardwareActionRejectedException} if the gate covered the call
     */
    public <T> T call(String label, Callable<T> action) throws Exception {
        return callBlocking(new String[] {label}, null, action, 0, null);
    }

    /**
     * {@link #call(Callable, long, TimeUnit)} with a gate label.
     *
     * @param label the gate label
     * @param action the callable to execute on the hardware thread
     * @param timeout maximum time to wait for the result
     * @param unit the timeout unit
     * @param <T> the result type
     * @return the value the callable produced
     * @throws IllegalArgumentException if the label is malformed or reserved
     * @throws Exception whatever the callable threw, or a gate rejection
     */
    public <T> T call(String label, Callable<T> action, long timeout, TimeUnit unit) throws Exception {
        return callBlocking(new String[] {label}, null, action, timeout, unit);
    }

    // ------------------------------------------------------------------
    // async with future — fire-and-forget that returns a value
    // ------------------------------------------------------------------

    /**
     * Schedule {@code action} on the hardware thread; return a future that
     * completes with the action's result. Non-blocking, so this is safe to call
     * from the hardware thread itself (unlike {@link #call(Callable)}).
     *
     * <p>If the gate drops the action (or a newer action with the same
     * {@linkplain Scope#slot slot} supersedes it), the future completes
     * exceptionally with a {@link HardwareActionRejectedException} carrying the
     * reason — it never hangs.
     *
     * @param action the callable to execute on the hardware thread
     * @param <T> the result type
     * @return a future that completes with the result, or exceptionally if the
     *         callable threw or the gate prevented it from running
     */
    public <T> CompletableFuture<T> callAsync(Callable<T> action) {
        return callAsync0(null, null, action);
    }

    /**
     * {@link #callAsync(Callable)} with a gate label.
     *
     * @param label the gate label
     * @param action the callable to execute on the hardware thread
     * @param <T> the result type
     * @return a future that completes with the result, or exceptionally on throw
     *         or gate rejection
     * @throws IllegalArgumentException if the label is malformed or reserved
     */
    public <T> CompletableFuture<T> callAsync(String label, Callable<T> action) {
        return callAsync0(new String[] {label}, null, action);
    }

    // ------------------------------------------------------------------
    // scoped submissions — labels + slot
    // ------------------------------------------------------------------

    /**
     * A lightweight scope binding labels to subsequent submissions. Every method
     * mirrors the unscoped one on {@link HardwareActions}, with the labels applied:
     *
     * <pre>{@code
     * hardware.labels("write", "climb").slot("claw").run(r);
     * }</pre>
     *
     * <p>Each configurator call returns a <i>new</i> scope with that property set
     * (labels replaced by {@link #labels}, slot replaced by {@link #slot}), so scopes
     * are cheap, immutable, and safe to keep. Labels and slots are both optional:
     * {@code hardware.slot("claw")} scopes by slot alone.
     */
    public static final class Scope {

        private final HardwareActions hw;
        private final String[] labels;
        private final String slot;

        Scope(HardwareActions hw, String[] labels, String slot) {
            this.hw = hw;
            this.labels = labels;
            this.slot = slot;
        }

        /**
         * @param labels the labels this scope carries (replacing any previous)
         * @return a new scope
         * @throws IllegalArgumentException if a label is malformed or reserved
         */
        public Scope labels(String... labels) {
            HardwareGate.validateLabels(labels);
            return new Scope(hw, labels.clone(), slot);
        }

        /**
         * @param slot the coalescing slot this scope claims (replacing any previous)
         * @return a new scope
         * @throws IllegalArgumentException if the slot is null or empty
         */
        public Scope slot(String slot) {
            if (slot == null || slot.isEmpty()) {
                throw new IllegalArgumentException("slot must not be null or empty");
            }
            return new Scope(hw, labels, slot);
        }

        /** {@link HardwareActions#run(Runnable)} with this scope's labels and slot. */
        public void run(Runnable action) {
            hw.submit(labels, slot, action, null, true);
        }

        /** {@link HardwareActions#call(Callable)} with this scope's labels and slot. */
        public <T> T call(Callable<T> action) throws Exception {
            return hw.callBlocking(labels, slot, action, 0, null);
        }

        /** {@link HardwareActions#call(Callable, long, TimeUnit)} with this scope. */
        public <T> T call(Callable<T> action, long timeout, TimeUnit unit) throws Exception {
            return hw.callBlocking(labels, slot, action, timeout, unit);
        }

        /** {@link HardwareActions#callAsync(Callable)} with this scope's labels and slot. */
        public <T> CompletableFuture<T> callAsync(Callable<T> action) {
            return hw.callAsync0(labels, slot, action);
        }

        /**
         * {@link HardwareActions#bulkRead(int, BulkReader)} with this scope's labels.
         * Ticks whose labels are restricted are skipped (never queued); slots do not
         * apply to periodic work.
         *
         * @param hz how often the reader runs (must be &gt; 0)
         * @param reader invoked on the hardware thread at the given rate
         * @return a handle for cancellation
         * @throws IllegalArgumentException if this scope carries a slot
         */
        public BulkReadHandle bulkRead(int hz, BulkReader reader) {
            if (slot != null) {
                throw new IllegalArgumentException(
                        "slots do not apply to periodic work; scope a bulkRead by labels only");
            }
            return hw.orchestrator.scheduleHardwareBulkRead(hz, reader, labels);
        }
    }

    /**
     * Scope subsequent submissions to the given labels. Labels are
     * {@code /}-separated paths; any number may be given, including none.
     *
     * @param labels the labels to bind
     * @return a scope whose {@code run/call/callAsync/bulkRead} carry these labels
     * @throws IllegalArgumentException if a label is malformed or reserved
     */
    public Scope labels(String... labels) {
        HardwareGate.validateLabels(labels);
        return new Scope(this, labels.clone(), null);
    }

    /**
     * Scope subsequent submissions to the given slot: at most one pending action per
     * slot exists, and only the most recent one is used.
     *
     * @param slot the slot to claim
     * @return a scope whose submissions carry this slot
     * @throws IllegalArgumentException if the slot is null or empty
     */
    public Scope slot(String slot) {
        if (slot == null || slot.isEmpty()) {
            throw new IllegalArgumentException("slot must not be null or empty");
        }
        return new Scope(this, null, slot);
    }

    // ------------------------------------------------------------------
    // bulk read — periodic hardware reads that publish to topics
    // ------------------------------------------------------------------

    /**
     * Schedule {@code reader} to be invoked on the hardware thread at
     * {@code hz} hertz. The reader receives a {@link HardwareView} it can
     * publish values through. Useful for sensor polling patterns:
     *
     * <pre>{@code
     * hw.bulkRead(50, view -> {
     *     double amps = motor.getCurrent(CurrentUnit.AMPS);
     *     view.publish("motor/amps", amps);
     * });
     * }</pre>
     *
     * <p>The reader runs on the hardware thread and shares it with all other
     * hardware work, so it cannot overlap with other hardware-thread callbacks
     * or {@code @RunPeriodically(hardware = true)} loops.
     *
     * <p>Returns a handle that can be passed to {@link #stopBulkRead} to cancel.
     *
     * @param hz how often the reader runs (must be &gt; 0)
     * @param reader invoked on the hardware thread at the given rate
     * @return a handle for cancellation
     */
    public BulkReadHandle bulkRead(int hz, BulkReader reader) {
        return orchestrator.scheduleHardwareBulkRead(hz, reader, null);
    }

    /**
     * Cancel a previously-registered bulk-read. Safe to call on an already-stopped
     * or null handle.
     *
     * @param handle the handle returned by {@link #bulkRead}
     */
    public void stopBulkRead(BulkReadHandle handle) {
        if (handle != null) handle.cancel();
    }

    // ------------------------------------------------------------------
    // gate — restrict / release labelled work
    // ------------------------------------------------------------------

    /**
     * Restrict {@code label} and all its sublabels: covered work cannot reach the
     * hardware thread until {@link #release(String)}. Sugar for
     * {@code restrict(label, mode, ChildPolicy.INDEPENDENT)}.
     *
     * @param label the label to restrict ({@link HardwareGate#ALL},
     *        {@link HardwareGate#UNLABELLED}, or a label path)
     * @param mode {@link GateMode#DROP} covered work, or {@link GateMode#QUEUE} it
     * @return true if applied; false (with a warning) if refused — malformed label
     *         or a sealed ancestor governs it
     */
    public boolean restrict(String label, GateMode mode) {
        return orchestrator.gate().restrict(label, mode);
    }

    /**
     * Restrict {@code label} and all its sublabels, with an explicit child policy.
     *
     * @param label the label to restrict
     * @param mode drop covered work or hold it
     * @param childPolicy {@link ChildPolicy#SEALED} to forbid gate operations on
     *        covered children while this restriction is active
     * @return true if applied; false (with a warning) if refused
     */
    public boolean restrict(String label, GateMode mode, ChildPolicy childPolicy) {
        return orchestrator.gate().restrict(label, mode, childPolicy);
    }

    /**
     * Fluent restriction form, for child policy and exceptions:
     *
     * <pre>{@code
     * hardware.restrict("shooter").drop().sealChildren()
     *         .except("shooter/write")
     *         .apply();
     * }</pre>
     *
     * @param label the label to restrict
     * @return a builder; {@code .drop()} or {@code .queue()} sets the mode and
     *         {@code .apply()} returns whether the restriction was applied
     */
    public HardwareGate.RestrictionBuilder restrict(String label) {
        return orchestrator.gate().restrict(label);
    }

    /**
     * Release the restriction at exactly {@code label}. Held work that becomes
     * uncovered runs in submission order; slotted work collapses to the latest per
     * slot first. A one-line summary of anything dropped while restricted is logged
     * here.
     *
     * @param label the label to release
     * @return true if a restriction was removed; false (with a warning) if refused
     */
    public boolean release(String label) {
        return orchestrator.gate().release(label);
    }

    /**
     * Whether an action labelled {@code label} would currently be covered by a
     * restriction (held or dropped). {@code isRestricted(HardwareGate.ALL)} answers
     * "is everything held"; {@code isRestricted(HardwareGate.UNLABELLED)} covers
     * label-less work.
     *
     * @param label the label to query
     * @return true if an active restriction covers it
     */
    public boolean isRestricted(String label) {
        return orchestrator.gate().isRestricted(label);
    }

    // ------------------------------------------------------------------
    // diagnostics — used by SafeOpMode to assert thread identity
    // ------------------------------------------------------------------

    /**
     * @return true if the current thread is the orchestrator's hardware thread.
     *         Used internally by {@code SafeOpMode} and {@code SafeDevice} to
     *         enforce thread safety.
     */
    public boolean isHardwareThread() {
        return orchestrator.isHardwareThread();
    }

    /**
     * Throw an {@link IllegalStateException} if the current thread is the
     * hardware thread. Useful in {@code OpMode.loop()} to fail-fast if the user
     * accidentally scheduled the loop on the hardware thread.
     */
    public void assertNotHardwareThread() {
        if (isHardwareThread()) {
            throw new IllegalStateException(
                    "This code is running on the hardware thread. OpMode.loop() " +
                    "and similar lifecycle hooks must run on the OpMode thread, " +
                    "publishing to topics. Use HardwareActions.run() to schedule " +
                    "hardware-thread work.");
        }
    }

    /**
     * Called once per OpMode loop iteration. Currently a no-op: the hardware thread
     * processes its own queue independently. Reserved for a future hook (e.g.
     * flushing telemetry). {@code SafeOpMode.loop()} calls this for you; if you
     * don't use {@code SafeOpMode}, call it yourself.
     */
    public void tick() {
        // Currently a no-op: the hardware thread processes its own queue
        // independently. Reserved for future hook (e.g. flushing telemetry).
    }

    /**
     * Shut down the underlying hardware thread — which, despite the name, closes
     * the <b>entire orchestrator</b> (all pools, all nodes), not just hardware
     * access. {@code SafeOpMode.stop()} already closes the orchestrator for you,
     * so you rarely need this.
     */
    public void shutdown() {
        orchestrator.close();
    }

    /** Handle returned from {@link #bulkRead}, used to cancel. */
    public static final class BulkReadHandle {
        private final java.util.concurrent.ScheduledFuture<?> future;
        public BulkReadHandle(java.util.concurrent.ScheduledFuture<?> f) { this.future = f; }
        public void cancel() { future.cancel(false); }
    }

    // ------------------------------------------------------------------
    // internals — everything funnels through the gate
    // ------------------------------------------------------------------

    private void submit(String[] labels, String slot, Runnable action,
                        Consumer<Exception> onReject, boolean queueable) {
        orchestrator.gate().submit(labels, slot, action, onReject, queueable);
    }

    private <T> T callBlocking(String[] labels, String slot, Callable<T> action,
                               long timeout, TimeUnit unit) throws Exception {
        final CompletableFuture<T> f = new CompletableFuture<>();
        final Exception[] rejection = new Exception[1];
        // queueable=false: a blocking call fails fast rather than waiting behind a hold.
        HardwareGate.Fate fate = orchestrator.gate().submit(labels, slot, () -> {
            try {
                f.complete(action.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        }, ex -> {
            rejection[0] = ex;
            f.completeExceptionally(ex);
        }, false);
        if (fate != HardwareGate.Fate.DISPATCHED) {
            // Fail fast: a blocking call never queues behind a gate hold, and it
            // never silently does nothing. The gate notified us synchronously.
            Exception e = rejection[0];
            if (e != null) {
                throw e;
            }
            throw new IllegalStateException("hardware action was not dispatched");
        }
        try {
            return timeout > 0 ? f.get(timeout, unit) : f.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (TimeoutException te) {
            f.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            // Unwrap so callers see the original exception.
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ee;
        }
    }

    private <T> CompletableFuture<T> callAsync0(String[] labels, String slot, Callable<T> action) {
        final CompletableFuture<T> f = new CompletableFuture<>();
        // queueable=true: a future may wait behind a QUEUE hold and complete on
        // release; a drop or slot supersede completes it exceptionally instead.
        orchestrator.gate().submit(labels, slot, () -> {
            try {
                f.complete(action.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        }, ex -> f.completeExceptionally(ex), true);
        return f;
    }
}
