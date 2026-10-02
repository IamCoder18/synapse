package com.aaravlabs.synapse;

import java.util.Optional;

/**
 * A typed pub/sub channel. Topics are addressed by string name and typed by
 * {@link Class}. Each topic remembers the most recent value published to it so that
 * nodes can {@link #latestValue()} it on demand instead of being forced to subscribe.
 *
 * <p>Topics are created by an {@link Orchestrator} via
 * {@link Orchestrator#getOrCreateTopic(String, Class)} — you should not construct them
 * directly.
 *
 * @param <T> the message type carried by this topic
 */
public final class Topic<T> {

    private final String name;
    private final Class<T> type;

    /**
     * {@code type} normalized to its wrapper class. Cached at construction so the
     * per-publish type check is a single field read instead of a primitive-boxing
     * chain.
     */
    private final Class<?> boxedType;

    /**
     * The most recently published value and its timestamp, as one immutable pair.
     *
     * <p>A single volatile reference rather than two volatile fields: with two, two
     * concurrent publishers can interleave between the value write and the timestamp
     * write and leave the pair describing two different publishes. Since
     * {@link Orchestrator#publish(String, Object)} is reachable from the OpMode
     * loop, the hardware thread, and the callback pool, that is not a theoretical
     * interleaving.
     *
     * <p>Immutable so that a reader holding a reference sees a self-consistent pair
     * with no further synchronization.
     */
    private volatile Latest<T> latest;

    Topic(String name, Class<T> type) {
        this.name = name;
        this.type = type;
        this.boxedType = box(type);
    }

    /** One publish's value and the {@link System#nanoTime()} at which it was recorded. */
    private static final class Latest<T> {
        final T value;
        final long publishNanos;

        Latest(T value, long publishNanos) {
            this.value = value;
            this.publishNanos = publishNanos;
        }
    }

    /**
     * @return the topic name
     */
    public String name() {
        return name;
    }

    /**
     * @return the topic's message type
     */
    public Class<T> type() {
        return type;
    }

    /**
     * The most recently published value, or empty if nothing has been published yet.
     *
     * <p><b>Reading a value together with its age:</b> each accessor reads a
     * self-consistent pair, but two separate calls can still straddle a publish. Sample
     * the timestamp <i>first</i>, which can only over-report the value's age:
     *
     * <pre>{@code
     * long stamp = topic.latestPublishNanos();  // read the timestamp FIRST
     * T v = topic.latestValueOr(null);         // then the value
     * long age = stamp == 0L ? Long.MAX_VALUE : System.nanoTime() - stamp;
     * }</pre>
     *
     * <p>Reading the timestamp first is what makes the pair safe: a publish landing
     * between the two calls can only over-report the age, never under-report it.
     *
     * <p>The {@code 0} guard handles "nothing published yet", where the stamp is still
     * its initial {@code 0} and subtracting it would yield the raw
     * {@link System#nanoTime()} reading. It is a heuristic, not a proof: the JLS permits
     * {@link System#nanoTime()} to return {@code 0}, so a genuine publish can carry a
     * {@code 0} stamp too. That case only over-reports the age, which rejects a fresh
     * value rather than admitting a stale one, so the failure direction is safe.
     *
     * Reading the value first is the unsafe order: a publish landing between the two
     * calls pairs the older value with the newer timestamp, so an age check on that
     * pair passes even though the value is stale.
     *
     * @return an {@link Optional} holding the latest value
     */
    public Optional<T> latestValue() {
        return Optional.ofNullable(latestValueOr(null));
    }

    /**
     * Nanos at which {@link #latestValue()} was last updated, on the
     * {@link System#nanoTime()} monotonic clock (arbitrary origin — only differences
     * are meaningful).
     *
     * <p>Sample this before {@link #latestValue()} when the two are used together as a
     * staleness check — see {@link #latestValue()} for the ordering rule.
     *
     * @return publish timestamp in nanoseconds, or {@code 0} if nothing has been published
     *         yet. Note that {@code 0} is also a value {@link System#nanoTime()} is
     *         permitted to return, so this cannot be used on its own to prove that no
     *         publish has occurred — see {@link #latestValue()}.
     */
    public long latestPublishNanos() {
        Latest<T> snap = latest;
        return snap == null ? 0L : snap.publishNanos;
    }

    /**
     * Record a new latest value. Called by the orchestrator immediately before notifying
     * subscribers.
     *
     * <p>The <b>read path is lock-free</b>: the value and its timestamp are published
     * together as one immutable {@link Latest} through a single volatile reference, so a
     * concurrent reader always sees a value and a timestamp from the same publish.
     * Splitting them across two volatile fields would let two concurrent publishers
     * interleave between the writes and leave the pair describing two different
     * publishes. The <b>write path takes the topic monitor</b>, around the clock sample,
     * one short-lived allocation and the store — see below. Each publish therefore
     * allocates; on the read side {@link #latestValueOr(Object)} allocates nothing,
     * while {@link #latestValue()} wraps its result in an {@link Optional}.
     *
     * @param value the value to record
     */
    void recordLatest(T value) {
        // The snapshot is sampled and installed under this topic's monitor so that
        // install order matches timestamp order. Without it, a publisher that samples
        // nanoTime() and is then preempted installs an OLDER snapshot after a newer
        // one, and a reader can end up holding an older value beside a newer
        // timestamp -- an age computed from that stamp is under-reported, so a
        // staleness check can pass a value that is already stale.
        //
        // The critical section is a clock read, an allocation and a volatile store --
        // NOT "two field writes and a clock read". Each publish allocates one Latest,
        // which is not scalar-replaceable because it escapes into a volatile field, so
        // garbage scales with publish rate across every topic.
        //
        // Hoisting the allocation above the monitor was measured and is not a win: it
        // gains ~12 ns/publish uncontended but loses ~10-20 ns under four publishers,
        // since it lengthens the window in which a stalled publisher holds a snapshot
        // whose stamp is already stale. The allocation stays inside.
        synchronized (this) {
            this.latest = new Latest<>(value, System.nanoTime());
        }
    }

    /**
     * @return true if a value of runtime class {@code actual} may be published here.
     *         Delegates to {@link #acceptsType} so the two cannot drift apart.
     */
    boolean acceptsValueClass(Class<?> actual) {
        return acceptsType(actual);
    }

    /** @return true if {@code other} is type-compatible with this topic. */
    boolean acceptsType(Class<?> other) {
        return boxedType.isAssignableFrom(box(other));
    }

    /**
     * Returns the most recent value, falling back to {@code defaultValue} if nothing has
     * been published yet. Convenience for {@code topic.latestValue().orElse(default)}.
     *
     * <p>Allocation-free — prefer this over {@link #latestValue()} on hot paths.
     *
     * @param defaultValue the value to return before the first publish
     * @return the latest value, or {@code defaultValue}
     */
    public T latestValueOr(T defaultValue) {
        Latest<T> snap = latest;
        return snap == null ? defaultValue : snap.value;
    }

    /** Treat primitive {@code double.class} and wrapper {@code Double.class} as the same type. */
    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == double.class)  return Double.class;
        if (c == float.class)   return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == char.class)    return Character.class;
        return c;
    }

    @Override
    public String toString() {
        return "Topic[" + name + ":" + type.getSimpleName() + "]";
    }
}
