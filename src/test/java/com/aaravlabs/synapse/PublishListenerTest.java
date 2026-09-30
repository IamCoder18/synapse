package com.aaravlabs.synapse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link PublishListener} extension point.
 *
 * <p>Two properties matter most and are easy to get wrong. A listener that
 * throws must not break the bus -- that is the difference between diagnostics
 * and an outage during a match. And the zero-listener path must stay cheap,
 * since this hook is on the hot path of every publish on the robot.
 */
class PublishListenerTest {

    @Test
    void listenerSeesEveryPublish() {
        Orchestrator orch = Orchestrator.create("listener");
        try {
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            orch.addPublishListener((topic, value, nanos) -> seen.add(topic + '=' + value));

            orch.publish("a", 1.0);
            orch.publish("b", "two");
            orch.publish("c", true);

            assertEquals(List.of("a=1.0", "b=two", "c=true"), seen);
        } finally {
            orch.close();
        }
    }

    @Test
    void listenerRunsBeforeSubscribersObserveTheValue() throws Exception {
        Orchestrator orch = Orchestrator.create("order");
        try {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            orch.addPublishListener((t, v, n) -> order.add("listener"));
            orch.subscribe("t", Double.class, v -> order.add("subscriber"));

            orch.publish("t", 1.0);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (order.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(List.of("listener", "subscriber"), order,
                    "the listener must fire before dispatch");
        } finally {
            orch.close();
        }
    }

    @Test
    void everyListenerGetsTheSameTimestamp() throws Exception {
        Orchestrator orch = Orchestrator.create("timestamps");
        try {
            // "Did it run" is recorded as a flag, never inferred from the
            // timestamp. System.nanoTime() has an arbitrary origin and its
            // values may be zero or negative, so `nanos > 0` can fail on a
            // perfectly good clock -- and when it does, it blames the clock for
            // a listener that ran perfectly well.
            AtomicBoolean slowRan = new AtomicBoolean();
            AtomicBoolean fastRan = new AtomicBoolean();
            AtomicLong first = new AtomicLong();
            AtomicLong second = new AtomicLong();

            orch.addPublishListener((t, v, nanos) -> {
                slowRan.set(true);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                first.set(nanos);
            });
            orch.addPublishListener((t, v, nanos) -> {
                fastRan.set(true);
                second.set(nanos);
            });

            orch.publish("t", 1.0);

            assertTrue(slowRan.get(), "the first listener should have run");
            assertTrue(fastRan.get(), "the second listener should have run");
            assertEquals(first.get(), second.get(),
                    "a slow first listener must not shift the timestamp later ones see");
        } finally {
            orch.close();
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakThePublish() throws Exception {
        Orchestrator orch = Orchestrator.create("throwing");
        try {
            // Plain ArrayList: the listener runs inline on the publishing
            // thread, which here is the test thread, so nothing else touches
            // this list. The subscriber below is dispatched to the callback
            // pool, so its counter has to be atomic.
            List<String> seen = new ArrayList<>();
            AtomicInteger subscriberCalls = new AtomicInteger();

            orch.addPublishListener((t, v, n) -> {
                throw new RuntimeException("listener is broken");
            });
            orch.addPublishListener((t, v, n) -> seen.add(t));
            orch.subscribe("t", Double.class, v -> subscriberCalls.incrementAndGet());

            orch.publish("t", 1.0);

            assertEquals(List.of("t"), seen, "listeners after the throwing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (subscriberCalls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, subscriberCalls.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void removeStopsNotifications() {
        Orchestrator orch = Orchestrator.create("remove");
        try {
            AtomicInteger calls = new AtomicInteger();
            PublishListener listener = (t, v, n) -> calls.incrementAndGet();

            orch.addPublishListener(listener);
            orch.publish("a", 1.0);
            assertEquals(1, calls.get());

            orch.removePublishListener(listener);
            orch.publish("b", 2.0);
            assertEquals(1, calls.get(), "no further notifications after removal");
        } finally {
            orch.close();
        }
    }

    @Test
    void removingSomethingNeverRegisteredIsHarmless() {
        Orchestrator orch = Orchestrator.create("remove-unknown");
        try {
            orch.removePublishListener((t, v, n) -> { });
            orch.removePublishListener(null);
        } finally {
            orch.close();
        }
    }

    @Test
    void nullListenersAreIgnored() {
        Orchestrator orch = Orchestrator.create("null-listener");
        try {
            orch.addPublishListener(null);
            orch.publish("t", 1.0);
        } finally {
            orch.close();
        }
    }

    @Test
    void listenersAreCalledInRegistrationOrder() {
        Orchestrator orch = Orchestrator.create("order-registration");
        try {
            // Plain ArrayList: registration order is only meaningful because
            // all three listeners run inline on this thread.
            List<String> order = new ArrayList<>();
            orch.addPublishListener((t, v, n) -> order.add("first"));
            orch.addPublishListener((t, v, n) -> order.add("second"));
            orch.addPublishListener((t, v, n) -> order.add("third"));

            orch.publish("t", 1.0);

            assertEquals(List.of("first", "second", "third"), order);
        } finally {
            orch.close();
        }
    }

    @Test
    void timestampIsPlausiblyCurrent() {
        Orchestrator orch = Orchestrator.create("now");
        try {
            long[] seen = new long[1];
            orch.addPublishListener((t, v, nanos) -> seen[0] = nanos);

            long before = System.nanoTime();
            orch.publish("t", 1.0);
            long after = System.nanoTime();

            // nanoTime values are only meaningful as differences: the origin
            // is arbitrary and the sequence wraps. Comparing raw values with
            // >= / <= happens to work while the counter is small and
            // positive, which is exactly why the bug is easy to miss.
            assertTrue(seen[0] - before >= 0 && after - seen[0] >= 0,
                    "listener timestamp " + seen[0] + " should fall within the publish window");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingANonFatalErrorStillDoesNotBreakThePublish() throws Exception {
        // The boundary is not Exception. On a robot the realistic way a
        // diagnostics module breaks is a failed assertion or a module that no
        // longer links against the robot build -- AssertionError,
        // NoClassDefFoundError, and friends are all Errors, and all of them
        // must be contained just like a RuntimeException is. This is what makes
        // narrowing the catch to Exception the wrong fix.
        Orchestrator orch = Orchestrator.create("throwing-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            orch.addPublishListener((t, v, n) -> {
                throw new NoClassDefFoundError("com/example/recorder/Recorder");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            AtomicInteger delivered = new AtomicInteger();
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "an Error from a listener must not break the publish");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingAVirtualMachineErrorIsNotContained() {
        // The one documented exception to "instrumentation can never break the
        // bus": once the JVM itself is compromised there is no useful publish
        // left to protect. Carrying on to the next listener would only allocate
        // more, so the fatal error is re-thrown rather than logged and ignored.
        // Pinned here so the carve-out stays deliberate and cannot widen by
        // accident.
        Orchestrator orch = Orchestrator.create("throwing-vm-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw new OutOfMemoryError("listener exhausted the heap");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertThrows(OutOfMemoryError.class, () -> orch.publish("t", 1.0),
                    "a JVM-fatal error from a listener must propagate, not be swallowed");
            assertFalse(afterRan.get(),
                    "the remaining listeners must not run once the JVM is out of memory");
            assertEquals(0, delivered.get(),
                    "subscriber dispatch must not run once the JVM is out of memory");
        } finally {
            orch.close();
        }
    }

    @Test
    void publishingWithNoListenerStillWorks() {
        Orchestrator orch = Orchestrator.create("no-listener");
        try {
            orch.publish("t", 1.0);
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow());
        } finally {
            orch.close();
        }
    }

    @Test
    void safeFromManyThreads() throws Exception {
        Orchestrator orch = Orchestrator.create("concurrent");
        try {
            AtomicInteger notifications = new AtomicInteger();
            List<String> topics = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                topics.add("topic/" + i);
            }
            // Exactly-once is the invariant worth pinning: too few means a
            // publish was lost from the notification path, too many means the
            // listener list was iterated more than once per publish.
            orch.addPublishListener((t, v, n) -> notifications.incrementAndGet());

            int threads = 6;
            int perThread = 500;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            orch.publish(topics.get((id + i) % topics.size()), (double) i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers should finish");

            assertEquals(threads * perThread, notifications.get(),
                    "every publish must be notified exactly once, no more and no fewer");
        } finally {
            orch.close();
        }
    }

    @Test
    void listenerSeesTheValueNotJustTheTopic() {
        Orchestrator orch = Orchestrator.create("value");
        try {
            List<Object> values = new ArrayList<>();
            orch.addPublishListener((t, v, n) -> values.add(v));

            String payload = "not interned";
            orch.publish("t", payload);

            assertEquals(1, values.size());
            assertSame(payload, values.get(0), "the listener receives the published reference");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerUnregisteringAnotherMidPublishDoesNotBreakTheBus() throws Exception {
        // Regression test, single-threaded and deterministic. The listener
        // loop originally iterated with size() and get(i), each of which reads
        // the current CopyOnWriteArrayList array independently. A listener
        // that unregistered a later one mid-publish left the cached size()
        // stale, and get(i) then threw IndexOutOfBoundsException. That call is
        // inside the try/catch, so the publish itself never failed -- but the
        // loop aborted there, every remaining listener was silently skipped
        // for that publish, and the log reported a listener as having thrown
        // when the list was merely shorter than expected. The loop now walks
        // one stable snapshot, so a publish notifies exactly the listeners
        // registered when it started.
        //
        // Registering `first` before `second` is what makes it deterministic:
        // `first` runs at index 0 and drops a listener that has not been
        // notified yet, so the old loop's cached size() of 2 was already stale
        // by the time it indexed. No threads, no timing, no retry.
        Orchestrator orch = Orchestrator.create("remove-during-publish", LogSink.SILENT);
        try {
            List<String> seen = new ArrayList<>();
            PublishListener second = (t, v, n) -> seen.add("second");
            PublishListener first = (t, v, n) -> {
                seen.add("first");
                orch.removePublishListener(second);
            };
            orch.addPublishListener(first);
            orch.addPublishListener(second);

            AtomicInteger delivered = new AtomicInteger();
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "mutating the listener list from inside a listener must not fail the publish");

            assertEquals(List.of("first", "second"), seen,
                    "every listener registered when the publish started must be notified, even one"
                    + " that a preceding listener unregistered mid-publish");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscriber dispatch must not be skipped");
        } finally {
            orch.close();
        }
    }

    @Test
    void concurrentListenerChurnNeverSurfacesAsAPublishFailure() throws Exception {
        // Bounded companion to the test above: mutating the listener list from
        // several threads at once must not surface as a failed publish, and
        // must not degrade into quadratic work.
        //
        // The listener set is fixed at four and registered once. An earlier
        // version of this test added a fresh throwing listener on every
        // iteration and never removed it, so the list grew without bound and
        // every publish iterated -- and stack-traced -- an ever longer prefix:
        // 8.2M logged exceptions and a 3.9G test result in 120 seconds, which
        // timed the test out before it reached a single assertion. Registering
        // two throwers makes the bound observable: their invocation count has
        // to stay linear in the number of publishes.
        Orchestrator orch = Orchestrator.create("churn", LogSink.SILENT);
        try {
            AtomicInteger notifications = new AtomicInteger();
            AtomicInteger throwerRuns = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();

            // Never unregistered, so it observes every publish exactly once.
            PublishListener observer = (t, v, n) -> notifications.incrementAndGet();
            // Removed and re-added by the churning threads.
            PublishListener churned = (t, v, n) -> { };
            PublishListener thrower = (t, v, n) -> {
                throwerRuns.incrementAndGet();
                throw new RuntimeException("always throws");
            };
            orch.addPublishListener(observer);
            orch.addPublishListener(churned);
            orch.addPublishListener(thrower);
            orch.addPublishListener(thrower);
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            int threads = 4;
            int perThread = 500;
            int publishes = threads * perThread;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger publishFailures = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            for (int t = 0; t < threads; t++) {
                final boolean churn = t % 2 == 0;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            if (churn) {
                                orch.removePublishListener(churned);
                                orch.addPublishListener(churned);
                            }
                            orch.publish("t", (double) i);
                        }
                    } catch (Throwable e) {
                        publishFailures.incrementAndGet();
                        firstFailure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers should finish");

            assertEquals(0, publishFailures.get(),
                    () -> "publish must never fail because of the listener list; first failure: "
                            + firstFailure.get());
            assertEquals(publishes, notifications.get(),
                    "the listener that is never unregistered must see every publish exactly once");
            assertTrue(throwerRuns.get() <= 2L * publishes,
                    "only the two registered throwers may run per publish, so their invocation count"
                            + " must stay linear in the publish count; got " + throwerRuns.get()
                            + " for " + publishes + " publishes");

            awaitCount(delivered, publishes);
            assertEquals(publishes, delivered.get(), "every publish must still reach its subscriber");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerSeesAPublishThatTypeValidationRejects() {
        // Listeners run before the topic's type is checked, so a publish the
        // bus rejects is still reported. That is deliberate: a type mismatch is
        // exactly the kind of fault a recording should be able to show, and
        // the caller still receives the exception.
        Orchestrator orch = Orchestrator.create("type-mismatch", LogSink.SILENT);
        try {
            List<Object> seen = new ArrayList<>();
            orch.subscribe("t", Double.class, v -> { });
            orch.addPublishListener((topic, value, nanos) -> seen.add(value));

            assertThrows(IllegalArgumentException.class, () -> orch.publish("t", "not a double"),
                    "the caller still sees the type mismatch");

            assertEquals(List.of("not a double"), seen,
                    "a publish rejected by type validation must still be visible to listeners");
        } finally {
            orch.close();
        }
    }

    /** Poll until {@code counter} reaches {@code target} or the deadline passes. */
    private static void awaitCount(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
    }

    @Test
    void aPublishOnAClosedOrchestratorDoesNotNotify() {
        Orchestrator orch = Orchestrator.create("closed");
        AtomicInteger calls = new AtomicInteger();
        orch.addPublishListener((t, v, n) -> calls.incrementAndGet());
        orch.close();

        orch.publish("t", 1.0);

        assertEquals(0, calls.get(),
                "a publish rejected because the bus is closed should not notify");
    }
}
