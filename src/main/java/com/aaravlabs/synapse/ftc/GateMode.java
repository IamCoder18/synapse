package com.aaravlabs.synapse.ftc;

/**
 * What a {@link HardwareGate gate} restriction does to the hardware-thread work it
 * covers while it is active.
 *
 * <ul>
 *   <li>{@link #DROP} — the work never reaches the hardware thread. Fire-and-forget
 *       work is discarded and counted; a {@link HardwareActions#callAsync callAsync}
 *       future completes exceptionally with
 *       {@link HardwareActionRejectedException.Reason#DROPPED_BY_GATE}; a blocking
 *       {@link HardwareActions#call call} throws it. Use for work that must never
 *       happen (emergency stop) or that is worthless once delayed.</li>
 *   <li>{@link #QUEUE} — the work is held and runs when the restriction no longer
 *       covers it, in submission order. Use for work that should still happen once
 *       it is safe (e.g. teleop setpoints accumulated until the match timer starts).
 *       Blocking {@link HardwareActions#call call} never waits behind a hold; it
 *       fails fast like {@link #DROP}.</li>
 * </ul>
 *
 * <p>Periodic work ({@code @RunPeriodically(hardware = true)} ticks and
 * {@link HardwareActions#bulkRead} readers) never queues: a tick is stale the moment
 * it is late, so a tick that arrives while its label is restricted is skipped under
 * either mode and the loop resumes on its normal schedule once released.
 *
 * @see HardwareGate
 */
public enum GateMode {
    /** Discard covered work; it never runs. */
    DROP,
    /** Hold covered work and run it when the restriction lifts. */
    QUEUE
}
