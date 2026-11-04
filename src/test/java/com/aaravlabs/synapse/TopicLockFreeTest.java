package com.aaravlabs.synapse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the lock-free {@link Topic} latest-value tracking: one volatile snapshot
 * instead of a monitor, and a boxed type cached at construction so the per-publish type
 * check is a single field read.
 */
class TopicLockFreeTest {

    private Orchestrator orchestrator;
    private Topic<Integer> topic;

    @BeforeEach void setUp() {
        orchestrator = Orchestrator.create("topic-lock-free");
        topic = orchestrator.getOrCreateTopic("concurrent", Integer.class);
    }
    @AfterEach  void tearDown() { orchestrator.close(); }

    @Test
    void latestValueAndTimestampAdvanceTogether() throws Exception {
        // Deliberately does not assume System.nanoTime() > 0: the JLS allows any
        // origin, so only *relative* comparisons are asserted.
        Topic<String> t = orchestrator.getOrCreateTopic("t", String.class);

        orchestrator.publish("t", "a");
        assertEquals("a", t.latestValueOr(null));
        long afterFirst = t.latestPublishNanos();

        // Do not assert that the first timestamp differs from a pre-publish reading:
        // System.nanoTime() may return the same tick for both reads on a coarse-resolution
        // platform, and the publish did record a timestamp. The value assertion above is
        // what proves the publish landed; the timestamp comparison is made below, after
        // the clock has demonstrably moved.
        //
        // Same value published again must still advance the timestamp — a cached
        // "unchanged" shortcut would break staleness checks. Wait for the clock to pass
        // the recorded stamp first (bounded, so a broken clock fails the assert instead
        // of hanging).
        long deadline = System.nanoTime() + 50_000_000L;
        while (afterFirst >= System.nanoTime() && System.nanoTime() <= deadline) Thread.sleep(1);
        orchestrator.publish("t", "a");
        assertTrue(t.latestPublishNanos() > afterFirst,
                "republishing an identical value must still advance latestPublishNanos");
    }

    @Test
    void concurrentPublishAndReadNeverLosesOrTearsTheLatestValue() throws Exception {
        // Topic reads are lock-free now (one volatile snapshot instead of a monitor on the
        // read path). Hammer it from several publishers while a reader samples
        // value+timestamp, and assert that a reader only ever observes a value that was
        // actually published, that the pair is never torn, and that the final state is
        // consistent.
        //
        // The pair is torn if a reader ends up holding a value OLDER than the timestamp it
        // sampled, which would let an age check pass a value that is already stale. Each
        // publisher publishes the globally unique ids in its own block and records the
        // clock reading at the end of its own publish of that id; keyed by id those never
        // move, so a timestamp later than the end of the value's own publish is
        // unambiguous evidence of a split pair.
        //
        // A timestamp EARLIER than the value's own publish is not a defect: that is the
        // reader sampling the timestamp, a publish landing, then reading the newer value.
        // It is the documented order and it over-reports age, which is safe.
        //
        // This is a detector, not a gate, and its sensitivity was measured rather than
        // assumed. Against a control that deliberately widens the interleaving window it
        // fires every run. Against the two-volatile design this branch replaces it fires
        // in roughly half the runs (~0.005% of samples), so a single green run here is
        // not evidence that the code is correct. What this test does establish is that no
        // reader ever observes a value outside the published range, and that the final
        // state is some publisher's last value.
        // Timestamp and value from ONE snapshot read, so there is no window for a
        // publish to land in between. This is the property the two-call accessors cannot
        // offer, and the reason latest() exists.
        final int publishers = 4;
        final int perPublisher = 25_000;
        final int ids = publishers * perPublisher;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> readersRunning = new AtomicReference<>(Boolean.TRUE);
        // Counted, and asserted non-zero below. Without this the whole reader loop can be
        // skipped forever: make latest() return empty and snap is always null, so no
        // assertion inside the loop ever runs and the test still passes on its final-state
        // checks. A detector that cannot be observed to have fired is not evidence.
        AtomicLong samplesWithValue = new AtomicLong();
        AtomicLongArray windowHi = new AtomicLongArray(ids);
        for (int i = 0; i < ids; i++) {
            // Long.MIN_VALUE, not -1: the JLS allows any origin for System.nanoTime(),
            // so a negative reading is legitimate and must stay distinguishable from
            // "not recorded yet".
            windowHi.set(i, Long.MIN_VALUE);
        }

        // One reader sampling as tightly as it can: sampling density is what makes the
        // tear detectable, so this loop deliberately does NOT yield. Thread.onSpinWait()
        // is a CPU-relief hint that never gives up the timeslice, so the reader does hold
        // a core while the publishers run, but only for the ~100ms they need, and the
        // explicit isAlive() assertions below turn a genuinely starved publisher into a
        // named failure rather than a misleading final-value assertion.
        //
        // Thread.yield() here would fix that starvation but weaken the detector, because
        // yielding is part of what closes the interleaving window being observed. So the
        // loop spins and the mitigation is a bounded spin plus the liveness assertions
        // below: a publisher that genuinely cannot finish now fails by name instead of
        // producing a misleading final-value failure.
        Thread reader = new Thread(() -> {
            try {
                while (readersRunning.get()) {
                    // ONE snapshot read, so the value and the timestamp provably come
                    // from the same publish. There is no window for a publish to land in,
                    // so this cannot over-report age the way two calls can.
                    Topic.Latest<Integer> snap = topic.latest().orElse(null);
                    if (snap == null) {
                        // Nothing published yet. There is deliberately no cross-check
                        // against latestPublishNanos() here: a publish can land between
                        // the two calls, so a nonzero stamp here would be that publish,
                        // not an inconsistency.
                    } else {
                        samplesWithValue.incrementAndGet();
                        Integer v = snap.value();
                        if (v < 0 || v >= ids) {
                            failure.compareAndSet(null,
                                    new AssertionError("torn/garbage latest value: " + v));
                            return;
                        }
                        // Only a stamp AFTER the publish ended indicates a torn pair: the
                        // reader would be holding an older value beside a newer timestamp.
                        long hi = windowHi.get(v);
                        if (hi != Long.MIN_VALUE && snap.publishNanos() > hi) {
                            failure.compareAndSet(null, new AssertionError(
                                    "value " + v + " paired with stamp " + snap.publishNanos()
                                            + " later than its publish end " + hi));
                            return;
                        }
                        // ageNanos must agree with the stamp it was taken from, so bracket
                        // the call between two clock reads and require the age to fall
                        // between both deltas. A one-sided check lets an implementation
                        // returning a constant 0 pass. Zero is legitimate (same tick).
                        long ageBefore = System.nanoTime();
                        long age = snap.ageNanos();
                        long ageAfter = System.nanoTime();
                        if (age < 0
                                || age < ageBefore - snap.publishNanos()
                                || age > ageAfter - snap.publishNanos()) {
                            failure.compareAndSet(null, new AssertionError(
                                    "implausible age " + age + " for stamp " + snap.publishNanos()));
                            return;
                        }
                    }
                    Thread.onSpinWait();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "topic-reader");
        reader.setDaemon(true);
        reader.start();

        Thread[] pubThreads = new Thread[publishers];
        for (int p = 0; p < publishers; p++) {
            final int base = p * perPublisher;
            pubThreads[p] = new Thread(() -> {
                try {
                    for (int i = 0; i < perPublisher; i++) {
                        orchestrator.publish("concurrent", base + i);
                        // Recorded after the publish returns. Recording it before would
                        // make the tear detector blind: a reader would essentially never
                        // see a value whose window had closed.
                        windowHi.set(base + i, System.nanoTime());
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "topic-publisher-" + p);
            pubThreads[p].start();
        }
        // Cleanup runs in finally: an assertion below can throw, and without this the
        // spinning reader keeps a core busy for the rest of the JVM's life while the
        // unjoined non-daemon publishers keep publishing -- slowing every later test and
        // potentially hanging JVM exit.
        try {
            // A publisher that does not finish leaves a mid-block value as the final
            // state, which would fail the assertion below for a reason that has nothing
            // to do with the code under test. Check liveness explicitly so the failure
            // names the thread.
            for (Thread t : pubThreads) {
                t.join(60_000);
                assertFalse(t.isAlive(), "publisher " + t.getName() + " did not finish within 60s");
            }

            assertNull(failure.get(), "concurrent access failed: " + failure.get());
            // The reader must actually have observed the topic. If it saw nothing, every
            // check above was vacuously skipped and this green run means nothing.
            assertTrue(samplesWithValue.get() > 0,
                    "the reader never observed a value, so the tear checks never ran");
            // Concurrent publishers each write a contiguous ascending block, so which
            // publisher's final write lands last is non-deterministic. The invariant is
            // that the surviving value is some publisher's last value, not that it is the
            // numerically largest one.
            int finalValue = orchestrator.getLatestValue("concurrent", Integer.class).orElse(-1);
            assertTrue(finalValue >= 0 && (finalValue + 1) % perPublisher == 0,
                    "final latest value " + finalValue
                            + " is not the last value of any publisher");
        } finally {
            // Signal the reader before joining it: it spins on this flag, so joining
            // first would wait out the timeout on every run. Cleanup only -- deliberately
            // no assertion here, because throwing from finally would mask whatever
            // failure the try block is reporting.
            readersRunning.set(Boolean.FALSE);
            reader.join(5_000);
            for (Thread t : pubThreads) {
                t.join(60_000);
            }
        }
    }

    /*
 * NOT a test: a record of why there is no test for the install-ordering guarantee.
 *
 * recordLatest holds the topic monitor across the clock sample and the store, so
 * install order equals timestamp order. Without it, a publisher that samples the clock
 * and is then preempted installs an OLDER snapshot after a newer one, and the visible
 * stamp moves backwards. That regression was reproduced against the real class by a
 * standalone probe, but it is not covered here, and this is the reasoning:
 *
 *  - The window between sampling and storing is nanoseconds wide in the steady state.
 *    Sampling `now` immediately before the store and asserting install order directly
 *    would test the test, not the code.
 *  - Detecting it by observation needs a reader tight enough to land inside that
 *    window. A spinning reader detects it 3 runs in 6, but starves the publishers on a
 *    1-2 core runner and made the suite hang. A yielding reader never detects it at all,
 *    because it samples orders of magnitude too slowly.
 *  - So every arrangement either flakes, hangs, or cannot fire. A test that cannot fail
 *    reliably is worse than no test: it reports coverage it does not have.
 *
 * The guarantee rests on the monitor in recordLatest plus the reasoning above, not on
 * an assertion. If that ever changes, this is the gap.
 */

    @Test
    void acceptsValueClassRejectsIncompatibleValues() {
        // The per-publish type check must keep rejecting incompatible values, and a
        // rejected value must not be recorded. Publishes reach acceptsValueClass with
        // a runtime class only, so a topic typed Number has to accept several
        // different concrete classes.
        orchestrator.getOrCreateTopic("n", Number.class);
        orchestrator.publish("n", 1);
        orchestrator.publish("n", 2L);
        orchestrator.publish("n", 3.5);
        assertEquals(3.5, orchestrator.getLatestValue("n", Number.class).orElse(null));

        assertThrows(IllegalArgumentException.class, () -> orchestrator.publish("n", "nope"));
        // The rejected String must not have been recorded.
        assertEquals(3.5, orchestrator.getLatestValue("n", Number.class).orElse(null));

        // A topic typed Integer must still reject a String after accepting Integers.
        orchestrator.getOrCreateTopic("i", Integer.class);
        orchestrator.publish("i", 1);
        orchestrator.publish("i", 2);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.publish("i", "nope"));
        assertEquals(2, orchestrator.getLatestValue("i", Integer.class).orElse(null));
    }

    @Test
    void acceptsValueClassTreatsPrimitivesAndWrappersAsEquivalent() {
        // acceptsValueClass boxes its argument, so it agrees with acceptsType for the
        // same pair even when handed a primitive class.
        Topic<Double> t = orchestrator.getOrCreateTopic("d", Double.class);
        assertTrue(t.acceptsValueClass(Double.class));
        assertTrue(t.acceptsValueClass(double.class), "primitive must match wrapper topic");
        assertTrue(t.acceptsType(double.class));
        // Literal expectations, not a comparison of acceptsValueClass against acceptsType:
        // acceptsValueClass delegates to acceptsType, so asserting they agree restates the
        // delegation rather than testing a truth value. These pin the actual semantics.
        assertFalse(t.acceptsValueClass(Number.class),
                "a supertype of the topic type must not be accepted as a publish type");
        assertFalse(t.acceptsValueClass(Object.class),
                "Object must not be accepted for a Double topic");

        Topic<Integer> i = orchestrator.getOrCreateTopic("i", Integer.class);
        assertFalse(i.acceptsValueClass(double.class), "Double must not match Integer topic");
    }

    @Test
    void latestReturnsValueAndTimestampFromOneConsistentPublish() throws Exception {
        // latest() exists so a staleness check reads one snapshot instead of two. It must
        // be empty before the first publish, agree with both single-field accessors, and
        // never need a 0 sentinel.
        Topic<String> t = orchestrator.getOrCreateTopic("snap", String.class);
        assertFalse(t.latest().isPresent(), "latest() must be empty before the first publish");

        // Clock readings taken OUTSIDE the library, around the publish. These are the only
        // independent reference for the stamp: comparing latest().publishNanos() with
        // latestPublishNanos() reads the same volatile field twice and holds for any value,
        // and an age bracket is offset-invariant -- a Latest stamped with publishNanos + K
        // shifts the age and both bounds by K and still passes. A wrong-but-consistent
        // timestamp is exactly the staleness failure latest() exists to prevent, so the
        // stamp has to be pinned against a clock the library did not touch.
        long publishBefore = System.nanoTime();
        orchestrator.publish("snap", "a");
        long publishAfter = System.nanoTime();

        Topic.Latest<String> first = t.latest().orElseThrow();
        assertEquals("a", first.value());
        assertEquals("a", t.latestValueOr(null));
        // Compare the SNAPSHOT identity, not the stamp. latest() and latestPublishNanos()
        // both read the same volatile field, so comparing their stamps holds for any value
        // an implementation could produce and would catch nothing. latestPublishNanos() is
        // pinned to an externally-clocked window below instead.

        // The stamp must fall inside the window this publish occupied, which no
        // self-consistent offset can satisfy. Pinned for both accessors, since neither
        // reading is derived from the other.
        assertTrue(first.publishNanos() >= publishBefore
                        && first.publishNanos() <= publishAfter,
                "publish stamp " + first.publishNanos() + " lies outside the publish window ["
                        + publishBefore + "," + publishAfter + "]");
        long recorded = t.latestPublishNanos();
        assertTrue(recorded >= publishBefore && recorded <= publishAfter,
                "latestPublishNanos() " + recorded + " must be the stamp of this publish,"
                        + " not a live clock reading, which would be outside ["
                        + publishBefore + "," + publishAfter + "]");

        // No assertion that the stamp is nonzero: System.nanoTime() has an arbitrary
        // origin, so a published snapshot may legitimately carry 0.

        // ageNanos must be the age of THIS value, from ITS OWN stamp. The clock reads
        // bracket the call itself -- a reading sampled outside it cannot bound the result
        // in either direction, because a pause between the reading and the call would
        // break the relation. Both bounds are relative to the clock rather than absolute
        // wall-clock deadlines: a snapshot read in the same tick as its own publish
        // legitimately reports age 0, and a GC pause between the publish and this read can
        // legitimately make the age arbitrarily large.
        long ageBefore = System.nanoTime();
        long age = first.ageNanos();
        long ageAfter = System.nanoTime();
        assertTrue(age >= 0, "ageNanos must not be negative: " + age);
        assertTrue(age >= ageBefore - first.publishNanos()
                        && age <= ageAfter - first.publishNanos(),
                "ageNanos " + age + " is not the age of stamp " + first.publishNanos()
                        + " measured between " + ageBefore + " and " + ageAfter);

        // A later publish replaces both halves together; there is no observable state in
        // which one half is from this publish and the other from the previous one.
        //
        // Wait for the clock to pass first.publishNanos() rather than sleeping a fixed
        // interval: System.nanoTime() may return the same tick for adjacent reads, so
        // Thread.sleep(2) does not guarantee the next publish gets a later stamp. Same
        // bounded-wait pattern as latestValueAndTimestampAdvanceTogether, so a broken
        // clock fails the assert instead of hanging.
        long deadline = System.nanoTime() + 50_000_000L;
        while (first.publishNanos() >= System.nanoTime() && System.nanoTime() <= deadline) {
            Thread.sleep(1);
        }
        orchestrator.publish("snap", "b");
        Topic.Latest<String> second = t.latest().orElseThrow();
        assertEquals("b", second.value());
        assertTrue(second.publishNanos() > first.publishNanos(),
                "a later publish must carry a later stamp");
        // Actually call ageNanos() on both snapshots -- deriving both from one clock reading
        // would reduce to a restatement of the strictly-later-stamp assertion above and
        // would never exercise ageNanos at all. The two calls sample the clock separately,
        // so the elapsed span between them is measured and allowed for: that span is the
        // sampling skew, and only skew of that size could let the newer value report the
        // older age. Clock readings either side keep the allowance honest rather than
        // granting an unbounded tolerance.
        long skewBefore = System.nanoTime();
        long secondAge = second.ageNanos();
        long firstAge = first.ageNanos();
        long skewAfter = System.nanoTime();
        long maxSkew = skewAfter - skewBefore;
        assertTrue(secondAge <= firstAge + maxSkew,
                "the newer value must not report an older age than the one it replaced: "
                        + secondAge + " vs " + firstAge + " (clock skew allowance " + maxSkew + ")");
    }

    @Test
    void acceptsTypeIsEquivalentToTheOldBoxedAssignableFromCheck() {
        // acceptsType() replaced `boxed(t.type()).isAssignableFrom(boxed(type))` at
        // four call sites. Primitive/wrapper equivalence has to survive.
        orchestrator.getOrCreateTopic("t", Double.class);
        orchestrator.publish("t", 0.5);
        assertTrue(orchestrator.findTopic("t", double.class).isPresent(),
                "Double topic must be findable as double");
        assertTrue(orchestrator.findTopic("t", Double.class).isPresent());
        assertEquals(0.5, orchestrator.getLatestValue("t", double.class).orElse(null));
        // Direction matters and is preserved: the check is
        // `boxed(topicType).isAssignableFrom(boxed(requestedType))`, so you must ask
        // with an equal-or-narrower type. Asking for the supertype Number finds
        // nothing — same as before this change.
        assertFalse(orchestrator.findTopic("t", Number.class).isPresent());
        assertFalse(orchestrator.findTopic("t", String.class).isPresent());
        assertFalse(orchestrator.findTopic("t", Object.class).isPresent());

        // Re-creating with an equivalent type is allowed; an incompatible one throws.
        assertNotNull(orchestrator.getOrCreateTopic("t", double.class));
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.getOrCreateTopic("t", String.class));
    }
}
