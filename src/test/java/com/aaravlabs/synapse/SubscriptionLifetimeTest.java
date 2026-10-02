package com.aaravlabs.synapse;

import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards three ways a subscription or typed lookup could outlive or misrepresent the
 * value it was given. All three were defects found by audit and reproduced here first.
 */
class SubscriptionLifetimeTest {

    private Orchestrator orchestrator;

    @BeforeEach void setUp() { orchestrator = Orchestrator.create("lifetime"); }
    @AfterEach  void tearDown() { orchestrator.close(); }

    @Test
    void subscribeWithAPrimitiveClassStillDelivers() {
        // The library treats primitive and wrapper types as interchangeable everywhere --
        // getOrCreateTopic accepts int.class for an Integer topic, and acceptsType boxes
        // before comparing. subscribe used to cast with the raw type instead, and
        // Class.cast() returns false for every argument when the class is primitive, so
        // every delivery threw ClassCastException. dispatchCallback swallowed it into a log
        // line: the subscriber was registered, never fired, and nothing said why.
        orchestrator.getOrCreateTopic("t", int.class);
        List<Integer> got = Collections.synchronizedList(new ArrayList<>());
        orchestrator.subscribe("t", int.class, got::add);

        orchestrator.publish("t", 1);
        orchestrator.publish("t", 2);
        await(() -> got.size() >= 2);

        assertEquals(List.of(1, 2), got,
                "subscribe with a primitive class must deliver, not silently drop");
    }

    @Test
    void unwrappingAnObjectTopicAsANarrowerTypeIsRefused() {
        // Object.isAssignableFrom(String) is true, so the types are "compatible" and the
        // old check let it through. The caller received a Topic<String> for a topic that
        // may hold any Object, and the erased cast failed later at their own call site --
        // after isPresent() had already reported a value. Refused up front instead.
        orchestrator.getOrCreateTopic("n", Object.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.getOrCreateTopic("n", String.class),
                "an Object topic must not be handed back as Topic<String>");
        assertTrue(e.getMessage().contains("Object"),
                "the message should name the actual type, was: " + e.getMessage());

        assertFalse(orchestrator.findTopic("n", String.class).isPresent(),
                "findTopic must not hand back a Topic<String> for an Object topic");
        assertTrue(orchestrator.findTopic("n", Object.class).isPresent(),
                "asking for the actual type must still work");

        orchestrator.publish("n", 1.5);
        assertFalse(orchestrator.getLatestValue("n", String.class).isPresent(),
                "getLatestValue must not report a Double as a String");
        assertEquals(1.5, orchestrator.getLatestValue("n", Object.class).orElse(null),
                "asking for the actual type must still return the value");

        // Narrowing to a type the topic can actually hold is still allowed.
        assertNotNull(orchestrator.getOrCreateTopic("n2", String.class));
        orchestrator.publish("n2", "ok");
        assertEquals("ok", orchestrator.getLatestValue("n2", String.class).orElse(null));
    }

    @Test
    void unregisterNodeStopsAnOnHardwareThreadSubscriber() throws Exception {
        // The hardware-thread wrapper replaces the list entry, but the Subscription still
        // reported the unwrapped handler, so removeSubscription searched for something the
        // list no longer held and returned without removing anything. An unregistered node
        // kept receiving publishes, so it could still drive the hardware -- and the pool is
        // never shut down by unregisterNode.
        AtomicInteger hits = new AtomicInteger();
        Node n = new Node(orchestrator) {
            @SubscribedTo(topic = "hw")
            @OnHardwareThread
            public void on(String s) { hits.incrementAndGet(); }
        };
        orchestrator.registerNode("n", n);
        orchestrator.publish("hw", "before");
        await(() -> hits.get() >= 1);

        orchestrator.unregisterNode("n");
        // Give any already-queued work time to drain before counting.
        Thread.sleep(200);
        int settled = hits.get();
        orchestrator.publish("hw", "after-unregister");
        Thread.sleep(400);

        assertEquals(settled, hits.get(),
                "an unregistered node must stop receiving, including on the hardware thread");
    }

    @Test
    void unsubscribeStopsAnOnHardwareThreadSubscriber() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Topic<String> t = orchestrator.getOrCreateTopic("hw2", String.class);
        Subscription sub = orchestrator.subscribe("hw2", String.class, s -> hits.incrementAndGet());
        // Route it through the same path the binder uses for @OnHardwareThread.
        ((OrchestratorImpl) orchestrator).markSubscriptionAsHardwareThreaded(sub);

        orchestrator.publish("hw2", "before");
        await(() -> hits.get() >= 1);

        sub.unsubscribe();
        Thread.sleep(200);
        int settled = hits.get();
        orchestrator.publish("hw2", "after-unsub");
        Thread.sleep(400);

        assertEquals(settled, hits.get(),
                "unsubscribe must stop a subscriber that was rerouted to the hardware thread");
        assertTrue(t.latestValue().isPresent(), "publish still records the latest value");
    }

    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
