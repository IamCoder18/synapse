package com.aaravlabs.synapse.ftc;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A generic wrapper around any FTC hardware object that routes every method
 * call through the orchestrator's hardware thread.
 *
 * <p>Use {@link SafeDevice#run(Consumer)} for fire-and-forget operations
 * (e.g. {@code device.run(m -> m.setPower(0.5))}) and {@link SafeDevice#call(Function)}
 * for reads that return a value (e.g. {@code device.call(DcMotorEx::getCurrentPosition)}).
 *
 * <p>Both methods submit the work to the single hardware thread and execute it
 * serially with respect to every other piece of hardware-thread work in the
 * orchestrator. This eliminates race conditions on the Lynx bus and other
 * non-thread-safe hardware APIs.
 *
 * <p>For advanced cases where you must call many methods atomically, use
 * {@link SafeDevice#raw()} to get the unwrapped object — but you must only
 * touch it from the hardware thread (e.g. inside a {@link HardwareActions#run}
 * callback or a {@code @OnHardwareThread} subscriber).
 *
 * <p>Example:
 * <pre>{@code
 * SafeDevice<DcMotorEx> intake = hardware.device(DcMotorEx.class, "intake");
 * intake.run(m -> m.setPower(0.5));                     // async, safe
 * int pos = intake.call(DcMotorEx::getCurrentPosition); // sync, blocks caller
 *
 * // With gate labels and a coalescing slot (see HardwareGate):
 * intake.labels("write", "intake").slot("intake-power").run(m -> m.setPower(p));
 * }</pre>
 *
 * @param <T> the wrapped hardware type (e.g. {@code DcMotorEx})
 */
public final class SafeDevice<T> {

    private final T raw;
    private final HardwareActions hardware;

    /**
     * Wrap a raw hardware object. Prefer {@link SafeHardwareMap#device} over
     * calling this directly.
     *
     * @param raw the raw device instance
     * @param hardware the hardware-thread facade to route through
     */
    public SafeDevice(T raw, HardwareActions hardware) {
        this.raw = raw;
        this.hardware = hardware;
    }

    /**
     * Schedule {@code action} to run on the hardware thread with this device as
     * its argument. Returns immediately.
     *
     * @param action receives the raw device
     */
    public void run(Consumer<? super T> action) {
        hardware.run(() -> action.accept(raw));
    }

    /**
     * Run {@code action} on the hardware thread, blocking the caller until it
     * completes. Returns the value produced by {@code action}.
     *
     * <p><b>Deadlock warning:</b> calling this from code that already runs on the
     * hardware thread (a {@code hardware = true} loop, an {@code @OnHardwareThread}
     * subscriber, or a bulk-read reader) blocks the hardware thread on itself and
     * hangs the robot. Use {@link #raw()} there instead.
     *
     * @param action receives the raw device and produces a value
     * @param <R> the result type
     * @return the value produced by {@code action}
     * @throws Exception whatever {@code action} threw
     */
    public <R> R call(Function<? super T, ? extends R> action) throws Exception {
        return hardware.call(() -> action.apply(raw));
    }

    /**
     * Apply {@code action} to the device on the hardware thread; return a
     * {@link java.util.concurrent.CompletableFuture} for the result. Non-blocking,
     * so this is safe to call from the hardware thread itself.
     *
     * @param action receives the raw device and produces a value
     * @param <R> the result type
     * @return a future that completes with the result
     */
    public <R> java.util.concurrent.CompletableFuture<R> callAsync(
            Function<? super T, ? extends R> action) {
        return hardware.callAsync(() -> action.apply(raw));
    }

    /**
     * Get the raw, unwrapped hardware object. <b>Use with caution</b>: any method
     * you call on the returned object runs on the caller's thread, not the hardware
     * thread. Only use this from inside a {@link HardwareActions#run} callback,
     * a {@code @OnHardwareThread} subscriber, a {@code hardware = true} loop, or
     * another piece of code already executing on the hardware thread.
     *
     * @return the unwrapped hardware object
     */
    public T raw() {
        return raw;
    }

    /**
     * Scope this device's calls to the given gate labels — the device-level mirror
     * of {@link HardwareActions#labels(String...)}.
     *
     * @param labels the labels to bind
     * @return a scope whose {@code run/call/callAsync} carry these labels
     * @throws IllegalArgumentException if a label is malformed or reserved
     */
    public Scope<T> labels(String... labels) {
        return new Scope<>(raw, hardware.labels(labels));
    }

    /**
     * Scope this device's calls to the given coalescing slot — the device-level
     * mirror of {@link HardwareActions#slot(String)}. At most one pending action per
     * slot exists; only the most recent one is used.
     *
     * @param slot the slot to claim
     * @return a scope whose calls carry this slot
     * @throws IllegalArgumentException if the slot is null or empty
     */
    public Scope<T> slot(String slot) {
        return new Scope<>(raw, hardware.slot(slot));
    }

    /**
     * A {@link SafeDevice} bound to gate labels and/or a slot. Configurator calls
     * return a new scope with that property set:
     * {@code device.labels("write").slot("intake-power").run(...)}.
     *
     * @param <T> the wrapped hardware type
     */
    public static final class Scope<T> {

        private final T raw;
        private final HardwareActions.Scope scope;

        Scope(T raw, HardwareActions.Scope scope) {
            this.raw = raw;
            this.scope = scope;
        }

        /** @return a new scope with these labels (replacing any previous) */
        public Scope<T> labels(String... labels) {
            return new Scope<>(raw, scope.labels(labels));
        }

        /** @return a new scope with this slot (replacing any previous) */
        public Scope<T> slot(String slot) {
            return new Scope<>(raw, scope.slot(slot));
        }

        /** {@link SafeDevice#run(Consumer)} with this scope's labels and slot. */
        public void run(Consumer<? super T> action) {
            scope.run(() -> action.accept(raw));
        }

        /** {@link SafeDevice#call(Function)} with this scope's labels and slot. */
        public <R> R call(Function<? super T, ? extends R> action) throws Exception {
            return scope.call(() -> action.apply(raw));
        }

        /** {@link SafeDevice#callAsync(Function)} with this scope's labels and slot. */
        public <R> java.util.concurrent.CompletableFuture<R> callAsync(
                Function<? super T, ? extends R> action) {
            return scope.callAsync(() -> action.apply(raw));
        }
    }

    @Override
    public String toString() {
        return "SafeDevice{" + raw.getClass().getSimpleName() + "}";
    }
}
