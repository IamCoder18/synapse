package com.aaravlabs.synapse;

/**
 * Notified synchronously on every {@link Orchestrator#publish} call, before any
 * subscriber dispatch.
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
 * <h2>Ordering</h2>
 * Listeners are called in registration order, after the null check and before
 * the topic's latest-value cache is updated, so a listener observes a publish
 * at the moment it was requested. They also run before the topic's type is
 * validated, so a publish rejected with {@code IllegalArgumentException} is
 * still reported; the caller still receives the exception.
 *
 * <p>One publish iterates a snapshot of the listener list taken when it
 * starts, so a listener unregistered part-way through is still notified for
 * the publish already in flight. It will not see the next one.
 *
 * <p>A listener that throws is caught, logged, and does not prevent delivery
 * to the other listeners or to subscribers -- instrumentation must never be
 * able to break the bus. That includes {@link Error}s: a diagnostics module
 * that fails an assertion or fails to link against the robot build throws
 * {@code AssertionError} or {@code NoClassDefFoundError}, not an
 * {@code Exception}, and must not take the bus down either.
 *
 * <p>The single exception is {@link VirtualMachineError}. If a listener leaves
 * the JVM out of memory or otherwise past recovery, there is no publish worth
 * protecting, so that error propagates out of {@code publish} instead of being
 * logged and stepped over.
 *
 * <h2>Cost</h2>
 * When no listener is registered the call costs a single volatile read, so this
 * is cheap enough to leave permanently wired on a competition robot.
 */
@FunctionalInterface
public interface PublishListener {

    /**
     * Called on every publish, before subscriber dispatch.
     *
     * @param topicName      the topic being published to
     * @param value          the published value; never null
     * @param timestampNanos a {@link System#nanoTime()} reading taken once at the
     *                       top of the publish, so every listener reports the
     *                       same instant regardless of its own latency
     */
    void onPublish(String topicName, Object value, long timestampNanos);
}
