package com.aaravlabs.synapse.ftc;

/**
 * What a {@link HardwareGate gate} restriction allows its <b>children</b> — the
 * labels beneath it in the label tree — while it is active.
 *
 * <ul>
 *   <li>{@link #INDEPENDENT} (the default) — children may be restricted and released
 *       freely while this restriction is active. The strictest restriction covering an
 *       action wins ({@link GateMode#DROP} beats {@link GateMode#QUEUE} beats running),
 *       so a child restriction can tighten or loosen only its own subtree.</li>
 *   <li>{@link #SEALED} — this restriction <b>alone</b> decides the fate of everything
 *       it covers. While it is active, {@code restrict} and {@code release} on covered
 *       descendants are refused (they return {@code false} and log a warning), and any
 *       child restrictions that existed before the seal are <b>suspended</b>: kept,
 *       not deleted, and re-applied the moment the seal lifts. Use for a master switch
 *       that nothing below it may renegotiate — an emergency stop, or a subsystem
 *       frozen for the duration of an action.</li>
 * </ul>
 *
 * <p>{@linkplain HardwareGate.RestrictionBuilder#except(String...) Exceptions} sit
 * outside both the restriction and its seal: an excepted subtree is uncovered <em>and</em>
 * independently gateable while the seal is active.
 *
 * @see HardwareGate
 */
public enum ChildPolicy {
    /** Children may be restricted and released independently (default). */
    INDEPENDENT,
    /** This restriction alone decides; gate operations on covered children are refused. */
    SEALED
}
