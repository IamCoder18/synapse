package com.aaravlabs.synapse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        final int publishers = 4;
        final int perPublisher = 25_000;
        final int ids = publishers * perPublisher;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> readersRunning = new AtomicReference<>(Boolean.TRUE);
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
                    // Timestamp FIRST, then value. On this order a publish landing
                    // between the two reads can only make the reported age too large,
                    // never too small, so a staleness check can never pass a stale value.
                    long ts = topic.latestPublishNanos();
                    Integer v = topic.latestValueOr(null);
                    if (v == null) {
                        if (ts != 0L) {
                            failure.compareAndSet(null, new AssertionError(
                                    "timestamp recorded with no value: " + ts));
                            return;
                        }
                    } else if (v < 0 || v >= ids) {
                        failure.compareAndSet(null,
                                new AssertionError("torn/garbage latest value: " + v));
                        return;
                    } else {
                        // Only a timestamp AFTER the window ends is a tear: the reader
                        // would be holding an older value beside a newer timestamp. A
                        // timestamp before the window is the safe, expected straddle --
                        // the reader sampled the timestamp, a publish landed, then it read
                        // the newer value.
                        long hi = windowHi.get(v);
                        if (hi != Long.MIN_VALUE && ts > hi) {
                            failure.compareAndSet(null, new AssertionError(
                                    "value " + v + " is older than the timestamp " + ts
                                            + " the reader sampled; its publish ended at " + hi));
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
                        // make the detector blind: a reader would essentially never see a
                        // value whose window had closed.
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
        // acceptsValueClass delegates to acceptsType, so the pair cannot disagree.
        for (Class<?> c : new Class<?>[]{Double.class, double.class, Number.class, Object.class}) {
            assertEquals(t.acceptsType(c), t.acceptsValueClass(c),
                    "acceptsValueClass must agree with acceptsType for " + c.getSimpleName());
        }

        Topic<Integer> i = orchestrator.getOrCreateTopic("i", Integer.class);
        assertFalse(i.acceptsValueClass(double.class), "Double must not match Integer topic");
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
