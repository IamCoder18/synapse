package com.aaravlabs.synapse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void listenerRunsBeforeSubscribersObserveTheValue() {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            orch.close();
        }
    }

    @Test
    void everyListenerGetsTheSameTimestamp() throws Exception {
        Orchestrator orch = Orchestrator.create("timestamps");
        try {
            long[] first = new long[1];
            long[] second = new long[1];
            List<Long> slow = new ArrayList<>();
            List<Long> fast = new ArrayList<>();

            orch.addPublishListener((t, v, nanos) -> {
                slow.add(nanos);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                first[0] = nanos;
            });
            orch.addPublishListener((t, v, nanos) -> {
                fast.add(nanos);
                second[0] = nanos;
            });

            orch.publish("t", 1.0);

            assertTrue(first[0] > 0, "the first listener should have run");
            assertTrue(second[0] > 0, "the second listener should have run");
            assertEquals(first[0], second[0],
                    "a slow first listener must not shift the timestamp later ones see");
        } finally {
            orch.close();
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakThePublish() {
        Orchestrator orch = Orchestrator.create("throwing");
        try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

            assertTrue(seen[0] >= before && seen[0] <= after,
                    "listener timestamp " + seen[0] + " should fall within the publish window");
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
