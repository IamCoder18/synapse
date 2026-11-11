package com.aaravlabs.synapse.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link SubscribedTo @SubscribedTo} method as touching FTC hardware.
 * The orchestrator will dispatch such callbacks on a <b>dedicated single thread</b>
 * shared by all hardware-thread callbacks and hardware-thread {@link
 * RunPeriodically @RunPeriodically} loops.
 *
 * <h2>Why this matters</h2>
 *
 * <p>FTC hardware (the Lynx bus, {@code DcMotorEx}, servos, I2C sensors, the IMU, …)
 * is <b>not</b> thread-safe. Calling {@code setPower}, {@code setPosition},
 * {@code hardwareMap.get(...)}, or reading any sensor from two threads at the same
 * time produces undefined behavior: race conditions, garbled serial-bus responses,
 * or {@code ConcurrentModificationException} crashes deep in the SDK.
 *
 * <p>The orchestrator offers three execution contexts:
 *
 * <ol>
 *   <li>The <b>callback pool</b> (4–16 threads) — for non-hardware subscriber
 *       callbacks.</li>
 *   <li>The <b>scheduler pool</b> — for non-hardware periodic loops.</li>
 *   <li>The <b>hardware thread</b> (single dedicated thread) — for any code that
 *       reads or writes {@code HardwareMap} devices. Use {@code @OnHardwareThread}
 *       on the method to route it here.</li>
 * </ol>
 *
 * <p>Code in a {@code @OnHardwareThread} callback is guaranteed to never overlap
 * with another piece of hardware-thread work; all hardware-thread tasks execute
 * strictly serially on the same OS thread.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * public class IntakeNode extends Node {
 *     private final DcMotorEx motor;
 *
 *     public IntakeNode(Orchestrator orchestrator, HardwareMap hwMap) {
 *         super(orchestrator);
 *         this.motor = hwMap.get(DcMotorEx.class, "intake");
 *     }
 *
 *     // Safe: this runs on the single hardware thread.
 *     @SubscribedTo(topic = "intake/set/power")
 *     @OnHardwareThread
 *     public void setPower(double power) {
 *         motor.setPower(power);
 *     }
 * }
 * }</pre>
 *
 * <p>Periodic loops that need to drive hardware (e.g. a 50 Hz PID controller
 * writing motor power every tick) should also opt in via {@code
 * @RunPeriodically(hardware = true)}, which routes the loop to the same single
 * thread.
 *
 * <h2>Labels and slots</h2>
 *
 * <p>{@link #labels()} gives each dispatched message gate labels, and
 * {@link #slot()} claims a coalescing slot — the same policy surface as
 * {@code HardwareActions.labels(...).slot(...)}:
 *
 * <pre>{@code
 * @SubscribedTo(topic = "drive/set/power")
 * @OnHardwareThread(labels = "write", slot = "drive-power")
 * public void onPower(double p) {
 *     motor.raw().setPower(p);
 * }
 * }</pre>
 *
 * <p>While a {@link com.aaravlabs.synapse.ftc.HardwareGate gate} restriction covers
 * {@code "write"}, dispatches are dropped or held per the restriction's
 * {@link com.aaravlabs.synapse.ftc.GateMode}; with a slot, only the newest
 * undelivered message is kept, so a flooded setpoint topic collapses to the latest
 * value instead of piling up. Labelling is optional — no labels means the gate can
 * only reach this method through {@code HardwareGate.ALL} /
 * {@code HardwareGate.UNLABELLED}.
 *
 * <h2>What this annotation does <em>not</em> do</h2>
 *
 * <ul>
 *   <li>It does <em>not</em> intercept ad-hoc hardware calls from your other
 *       methods. If you write {@code motor.setPower(0)} inside a regular
 *       {@code @SubscribedTo} callback that runs on the callback pool, that
 *       write still happens on the callback-pool thread. You must annotate
 *       every method that touches hardware.</li>
 *   <li>It does <em>not</em> protect against the OpMode {@code loop()} also
 *       touching the same hardware. If your OpMode loop reads/writes hardware
 *       directly, that runs on the loop thread, separate from the hardware
 *       thread. Either move that work into a {@code Node} with this annotation
 *       or stop touching hardware from the loop.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnHardwareThread {

    /**
     * Gate labels for each dispatched message, as {@code /}-separated paths
     * ({@code "write"}, {@code "write/drive"}). While a gate restriction covers any
     * of them, dispatches of this method are dropped or held per the restriction's
     * {@link com.aaravlabs.synapse.ftc.GateMode}. May be empty (the default): no
     * labels. {@link com.aaravlabs.synapse.ftc.HardwareGate#ALL} and
     * {@link com.aaravlabs.synapse.ftc.HardwareGate#UNLABELLED} are reserved and
     * cannot appear here. Malformed labels fail fast at {@code registerNode}.
     */
    String[] labels() default {};

    /**
     * Coalescing slot: at most one undelivered dispatch per slot exists — a newer
     * message supersedes an older one that has not started, so a flooded topic
     * collapses to its latest value. Opaque string, no hierarchy. Empty (the
     * default) means no slot. Slots do not apply to periodic work.
     */
    String slot() default "";
}
