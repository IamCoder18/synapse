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
 * at the moment it was requested. A listener that throws is caught, logged, and
 * does not prevent delivery to the other listeners or to subscribers --
 * instrumentation must never be able to break the bus.
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
