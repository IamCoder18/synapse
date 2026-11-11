package com.aaravlabs.synapse.ftc;

/**
 * Reports that a hardware-thread action was prevented from running — by a
 * {@link HardwareGate gate} restriction or by a newer action taking its
 * {@linkplain HardwareActions.Scope#slot slot} — so callers always find out why
 * their work did not happen instead of waiting for a result that will never come.
 *
 * <p>One rule governs every hardware-thread submission: <b>an action runs, or its
 * caller finds out why it didn't — and nothing crashes.</b> Concretely:
 *
 * <ul>
 *   <li>{@link HardwareActions.Scope#run run} is fire-and-forget: the action is
 *       discarded and counted, and the drop is summarized in one warning when the
 *       label is released. There is no caller to fail.</li>
 *   <li>{@link HardwareActions.Scope#callAsync callAsync}: the returned future
 *       completes exceptionally with this exception. An unobserved failed future is
 *       inert — no thread throws, nothing dies.</li>
 *   <li>{@link HardwareActions.Scope#call call} fails fast with this exception
 *       (checked). It never waits behind a held gate, and the compiler forces every
 *       caller to handle the failure.</li>
 * </ul>
 *
 * <p>This is a {@linkplain Exception checked} exception on purpose: it is the
 * hardware-thread equivalent of "the shop is closed", and callers must decide what
 * to do about it rather than discover it three frames up as a mystery hang.
 */
public class HardwareActionRejectedException extends Exception {

    /** Why the action was prevented from running. */
    public enum Reason {
        /**
         * A {@link HardwareGate} restriction covered the action
         * ({@link GateMode#DROP}, or {@link GateMode#QUEUE} where queueing was not an
         * option — blocking {@code call} never waits behind a hold).
         */
        DROPPED_BY_GATE,
        /**
         * A newer action with the same {@linkplain HardwareActions.Scope#slot slot}
         * replaced this one before it started. Not an error condition: it is what
         * slot coalescing means — only the most recent action per slot is used.
         */
        SUPERSEDED
    }

    private final Reason reason;

    /**
     * @param reason why the action did not run
     * @param message human-readable detail (which label or slot, and what to do about it)
     */
    public HardwareActionRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * @return why the action did not run
     */
    public Reason reason() {
        return reason;
    }
}
