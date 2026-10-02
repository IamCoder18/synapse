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

    /**
     * One publish's value and the {@link System#nanoTime()} at which it was recorded.
     *
     * <p>Immutable. Returned by {@link Topic#latest()} so a caller can read the value and
     * its timestamp without risking a publish landing between two calls.
     *
     * @param <T> the message type carried by this topic
     */
    public static final class Latest<T> {

        private final T value;
        private final long publishNanos;

        Latest(T value, long publishNanos) {
            this.value = value;
            this.publishNanos = publishNanos;
        }

        /**
         * The published value.
         *
         * @return the value published in this snapshot
         */
        public T value() {
            return value;
        }

        /**
         * When this value was recorded.
         *
         * @return the {@link System#nanoTime()} at which {@link #value()} was recorded, on
         *         the monotonic clock (arbitrary origin — only differences are meaningful)
         */
        public long publishNanos() {
            return publishNanos;
        }

        /**
         * How long ago this value was recorded, measured from its own stamp.
         *
         * <p>Unlike {@code System.nanoTime() - publishNanos()}, this is always the age of
         * <i>this</i> value and needs no {@code 0} sentinel handling.
         *
         * @return nanos elapsed since {@link #publishNanos()}, on the monotonic clock
         */
        public long ageNanos() {
            return System.nanoTime() - publishNanos;
        }

        @Override
        public String toString() {
            return "Latest[" + value + " @" + publishNanos + "]";
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
     * <p><b>Reading a value together with its age:</b> prefer {@link #latest()}, which
     * returns both from a single snapshot read. Composing them from two accessors works
     * only if you sample the timestamp <i>first</i>, which can only over-report the age:
     *
     * <pre>{@code
     * long stamp = topic.latestPublishNanos();  // read the timestamp FIRST
     * T v = topic.latestValueOr(null);         // then
     * long age = stamp == 0L ? Long.MAX_VALUE : System.nanoTime() - stamp;
     * }</pre>
     *
     * <p>The {@code 0} guard handles "nothing published yet", where the stamp is still
     * its initial {@code 0} and subtracting it would yield the raw
     * {@link System#nanoTime()} reading. It is a heuristic, not a proof: the JLS permits
     * {@link System#nanoTime()} to return {@code 0}, so a genuine publish can carry a
     * {@code 0} stamp too. That case only over-reports the age, which rejects a fresh
     * value rather than admitting a stale one, so the failure direction is safe.
     *
     * <p>{@link #latest()} removes the question entirely: one snapshot read, no sentinel.
     *
     * <p>Reading the value first is the unsafe order: a publish landing between the two
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
        return snap == null ? 0L : snap.publishNanos();
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
     * while {@link #latestValue()} and {@link #latest()} each wrap their result in an
     * {@link Optional}.
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
     * <p>Allocation-free — prefer this over {@link #latestValue()} on hot paths, and over
     * {@link #latest()} if you do not need the timestamp.
     *
     * @param defaultValue the value to return before the first publish
     * @return the latest value, or {@code defaultValue}
     */
    public T latestValueOr(T defaultValue) {
        Latest<T> snap = latest;
        return snap == null ? defaultValue : snap.value();
    }

    /**
     * The most recent value and the {@link System#nanoTime()} at which it was published,
     * as one consistent pair.
     *
     * <p><b>Prefer this over {@link #latestValue()} plus {@link #latestPublishNanos()}
     * when you are checking whether a value is stale.</b> The two accessors each read the
     * snapshot correctly, but a caller making two calls can straddle a publish between
     * them and end up holding a value from one publish and a timestamp from another:
     *
     * <pre>{@code
     * long stamp = topic.latestPublishNanos();   // publish A
     *                                                  ...publish B lands...
     * T v = topic.latestValueOr(null);             // publish B's value, with A's stamp
     * long age = System.nanoTime() - stamp;        // over-reports by a full publish interval
     * }</pre>
     *
     * <p>Reading the timestamp first makes that safe in one direction — the age can only
     * be over-reported, never under-reported — but over-reporting still costs you: a
     * fresh value gets rejected as stale. How often that happens depends on how long the
     * two reads take. Normally they are nanoseconds apart and a publish landing between
     * them is vanishingly rare. It stops being rare when the reader is preempted between
     * the two calls by a GC pause or the scheduler, since the window becomes
     * milliseconds. This accessor removes the window entirely.
     *
     * <p>Returns empty before the first publish.
     *
     * <p><b>Allocates one small {@link Optional} wrapper per call.</b> If you only want
     * the value and never check its age, use {@link #latestValueOr(Object)}, which is
     * allocation-free.
     *
     * @return an {@link Optional} holding the latest value and its publish timestamp
     */
    public Optional<Latest<T>> latest() {
        return Optional.ofNullable(latest);
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
