package com.aaravlabs.synapse;

/**
 * Notified synchronously on every {@link Orchestrator#publish} that reaches the
 * bus, before any subscriber dispatch.
 *
 * <p>Pluggable like {@link LogSink}, and for the same reason: recording,
 * metrics, and tracing all need to observe the bus, and none of them should have
 * to reimplement {@code publish} to do it. Intended for diagnostics rather than
 * for business logic.
 *
 * <h2>Threading</h2>
 * Listeners run <b>on the publishing thread</b>, which may be the OpMode loop
 * thread, the hardware thread, or a callback-pool thread. They must be
 * thread-safe and must not block: a listener that sleeps delays a publish.
 * Doing real work should mean handing the value to a queue and returning.
 *
 * <h2>When a listener is called</h2>
 * A listener is called for a publish that actually reaches the bus. Two calls
 * are rejected before that point and are <b>not</b> observable here, so a
 * recorder sees neither of them:
 * <ul>
 *   <li>a publish to a {@linkplain Orchestrator#isClosed() closed} orchestrator
 *       -- it logs a warning and returns;</li>
 *   <li>a publish of a {@code null} value -- it throws
 *       {@code IllegalArgumentException}.</li>
 * </ul>
 * Both are argument validation: no topic is resolved, no latest value recorded,
 * nothing dispatched. In the {@code null} case there is also no value to hand
 * the listener, which is why {@link #onPublish} documents its value as never
 * null.
 *
 * <p>A third call <b>is</b> observable, and this is deliberate: listeners run
 * <i>before</i> the topic's type is validated, so a publish rejected with
 * {@code IllegalArgumentException} for a type mismatch is still reported. A
 * type mismatch is a fault in an otherwise real publish, and a recording
 * should be able to show it. The caller still receives the exception either way.
 *
 * <p>Within a reported publish, listeners are called in registration order and
 * before the topic's latest-value cache is updated, so a listener observes a
 * publish at the moment it was requested.
 *
 * <p>One publish iterates a snapshot of the listener list taken when it
 * starts, so a listener unregistered part-way through is still notified for
 * the publish already in flight. It will not see the next one.
 *
 * <h2>Containment</h2>
 * A listener that throws is caught, logged, and does not prevent delivery
 * to the other listeners or to subscribers -- instrumentation must never be
 * able to break the bus. That includes {@link Error}s: a diagnostics module
 * that fails an assertion or fails to link against the robot build throws
 * {@code AssertionError} or {@code NoClassDefFoundError}, not an
 * {@code Exception}, and must not take the bus down either.
 *
 * <p>The single exception is {@link OutOfMemoryError}, and only that. Heap
 * exhaustion is the one condition where there is no publish worth protecting,
 * because recovery itself needs memory: logging the error builds a message and
 * fills in a stack trace, so swallowing it would fail again in a worse place.
 * Every other {@link VirtualMachineError} is contained. {@link
 * StackOverflowError} is the common one and is routinely recoverable -- an
 * unbounded recursion in a listener unwinds that listener's frames and leaves
 * the stack whole, with the heap never touched -- so failing the publish, and
 * with it every subscriber, would be the hook causing the outage it exists to
 * diagnose. {@link InternalError} and {@link UnknownError} are contained for
 * a different reason: both are documented as serious VM failures, and no
 * subclass of either marks the fatal instance, so {@code publish} cannot tell
 * a fatal one from a benign one and does not guess. Silence in the hierarchy
 * is not evidence that these are harmless, and is not read as such. What the
 * hierarchy does settle is the family they belong to: neither is an {@link
 * OutOfMemoryError}, so no instance of either can reach the one rethrow
 * above, and containment is the whole of the decision. The JDK image is
 * scanned across every module in the boot layer for that answer, not
 * {@code java.base} alone, because {@code catch (OutOfMemoryError)} matches
 * subclasses from any module: {@code UnknownError} has no subclass there at
 * all, and the sole {@link InternalError} subclass is
 * {@link java.util.zip.ZipError}. {@code ZipError} is named as a fact about
 * that family, not as a hazard this hook will meet -- the JDK documents it as
 * no longer used and obsolete, superseded by {@code ZipException}, so a
 * corrupt archive raises something else today. What can be told is that the
 * bus is not compromised -- the fault is inside one listener's frame, and
 * nothing else on the bus depends on it. Re-throwing by type rather than by
 * consequence was the bug; see {@code OrchestratorImpl.publish}.
 *
 * <h2>Cost</h2>
 * When no listener is registered the call costs a single volatile read, so this
 * is cheap enough to leave permanently wired on a competition robot.
 */
@FunctionalInterface
public interface PublishListener {

    /**
     * Called on every publish that reaches the bus, before subscriber dispatch.
     * Not called for a publish to a closed orchestrator, nor for one rejected
     * for a {@code null} value; see the class documentation.
     *
     * @param topicName      the topic being published to
     * @param value          the published value; never null
     * @param timestampNanos a {@link System#nanoTime()} reading taken once at the
     *                       top of the publish, so every listener reports the
     *                       same instant regardless of its own latency
     */
    void onPublish(String topicName, Object value, long timestampNanos);
}
