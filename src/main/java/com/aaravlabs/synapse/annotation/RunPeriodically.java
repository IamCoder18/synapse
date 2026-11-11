package com.aaravlabs.synapse.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be invoked on a fixed-rate loop at up to {@link #hz()} hertz.
 *
 * <p>Methods must take no parameters and return void, and must not be static. They run
 * on the orchestrator's dedicated scheduling thread pool, separate from the callback
 * pool used to deliver {@link SubscribedTo} callbacks.
 *
 * <p>The implementation uses fixed-delay scheduling: the next invocation starts
 * {@code 1000 / hz} milliseconds after the previous one <em>finishes</em>, so a slow
 * loop will not overlap itself, and the effective rate is {@code hz} or lower. The
 * first invocation happens immediately after registration. Exceptions thrown by the
 * loop body are logged and the loop keeps running.
 *
 * <p>Set {@link #hardware()} to {@code true} to route the loop to the orchestrator's
 * <b>single dedicated hardware thread</b>. Use this for periodic loops that read or
 * write FTC hardware — for example, a 50 Hz drive loop that sets motor power every
 * tick. See {@link OnHardwareThread} for the full rationale.
 *
 * <p>{@link #labels()} gives hardware ticks gate labels. While a
 * {@link com.aaravlabs.synapse.ftc.HardwareGate gate} restriction covers a label,
 * the tick is <b>skipped</b> — never queued, because a tick is stale the moment it
 * is late — and the loop resumes on its normal schedule once released. Labels have
 * no effect without {@code hardware = true} (the gate governs the hardware thread).
 *
 * <p>Example:
 * <pre>{@code
 * @RunPeriodically(hz = 50)
 * public void update() {
 *     // math or state machines — no hardware access
 * }
 *
 * @RunPeriodically(hz = 50, hardware = true, labels = "write")
 * public void drive() {
 *     fl.run(m -> m.setPower(powerFL));
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(RunPeriodicallys.class)
public @interface RunPeriodically {
    int hz() default 20;

    /**
     * If {@code true}, the loop runs on the orchestrator's single dedicated
     * hardware thread instead of the scheduler pool. All {@code hardware=true}
     * loops in the entire orchestrator share that one thread and execute
     * strictly serially.
     */
    boolean hardware() default false;

    /**
     * Gate labels for hardware ticks, as {@code /}-separated paths. While a gate
     * restriction covers any of them, that tick is skipped (DROP and QUEUE alike —
     * ticks are never held) and the loop resumes on release. May be empty (the
     * default): no labels. Only meaningful with {@code hardware = true};
     * {@link com.aaravlabs.synapse.ftc.HardwareGate#ALL} and
     * {@link com.aaravlabs.synapse.ftc.HardwareGate#UNLABELLED} are reserved.
     * Malformed labels fail fast at {@code registerNode}.
     */
    String[] labels() default {};
}
