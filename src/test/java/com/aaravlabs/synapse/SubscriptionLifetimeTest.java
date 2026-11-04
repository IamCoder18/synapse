package com.aaravlabs.synapse;

import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

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
        // Class.cast() throws for any non-null argument when the class is primitive, so
        // every delivery threw ClassCastException. dispatchCallback swallowed it into a log
        // line: the subscriber was registered, never fired, and nothing said why.
        orchestrator.getOrCreateTopic("t", int.class);
        List<Integer> got = Collections.synchronizedList(new ArrayList<>());
        orchestrator.subscribe("t", int.class, got::add);

        orchestrator.publish("t", 1);
        orchestrator.publish("t", 2);
        await(() -> got.size(), "both published values must be delivered");

        // Membership, not order: publish dispatches each callback to a four-worker pool,
        // so the second task can run before the first. TopicTest documents the same.
        assertEquals(2, got.size(), "both published values must be delivered exactly once");
        assertEquals(Set.of(1, 2), Set.copyOf(got),
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
    void publishToABinderCreatedObjectTopicStillSucceeds() {
        // The cast-safety rule protects typed lookups, but publish is not one: it
        // downcasts to Topic<Object> immediately and never exposes the topic to a caller.
        // A zero-argument @SubscribedTo handler makes the binder register an Object-typed
        // topic, so enforcing the rule on publish's lazy auto-create would reject String
        // against that topic and drop a publish that has always worked.
        //
        // Scope of this test: it pins the observable behaviour — publish to an
        // Object-typed topic delivers and records. The specific defect was on the lazy
        // auto-create branch, reachable only when a binder installs the Object topic
        // between publish's topics.get and its create. This test reaches the topic via
        // the map-hit branch instead, so it does not by itself detect a revert of that
        // one line; it guards the behaviour the revert would break, and the line itself is
        // covered by review rather than by a test that would have to win a race.
        AtomicInteger hits = new AtomicInteger();
        Node n = new Node(orchestrator) {
            @SubscribedTo(topic = "any")
            @OnHardwareThread
            public void on() { hits.incrementAndGet(); }
        };
        orchestrator.registerNode("n", n);
        assertEquals(Object.class, orchestrator.findTopic("any").orElseThrow().type(),
                "a zero-argument handler must give the topic the Object type");

        // No assertDoesNotThrow: the publish must simply deliver, and the await below is
        // the assertion. A thrown IllegalArgumentException fails the test on its own.
        orchestrator.publish("any", "a string on an Object topic");
        await(hits::get, "the Object-typed topic must still deliver to its subscriber");
        assertEquals("a string on an Object topic",
                orchestrator.getLatestValue("any", Object.class).orElse(null),
                "publish must still record the latest value");
    }

    @Test
    void unregisterNodeStopsAnOnHardwareThreadSubscriber() {
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
        // Control subscriber, never unregistered. It is what makes the negative assertion
        // below falsifiable: its arrival proves the post-unregister publish was dispatched
        // and the hardware thread was live, so a silent subject means the subject was
        // removed rather than the bus having gone quiet. Without it, "nothing arrived" is
        // also what a starved pool looks like, and the test would pass either way.
        AtomicInteger control = new AtomicInteger();
        Node ctl = new Node(orchestrator) {
            @SubscribedTo(topic = "hw")
            @OnHardwareThread
            public void on(String s) { control.incrementAndGet(); }
        };

        orchestrator.registerNode("n", n);
        orchestrator.registerNode("ctl", ctl);
        orchestrator.publish("hw", "before");
        // Positive control, asserted by await: the callback really ran before removal.
        await(hits::get, "the hardware-thread callback must run before removal");
        await(control::get, "the control callback must run before removal");
        assertEquals(1, hits.get(), "exactly the one pre-unregister publish should have been seen");

        orchestrator.unregisterNode("n");
        int settled = hits.get();
        orchestrator.publish("hw", "after-unregister");
        await(control::get,
                "the control must observe the post-unregister publish, proving dispatch ran");

        assertEquals(settled, hits.get(),
                "an unregistered node must stop receiving, including on the hardware thread");
    }

    @Test
    void unsubscribeStopsAnOnHardwareThreadSubscriber() {
        AtomicInteger hits = new AtomicInteger();
        Topic<String> t = orchestrator.getOrCreateTopic("hw2", String.class);
        Subscription sub = orchestrator.subscribe("hw2", String.class, s -> hits.incrementAndGet());
        // Control subscriber that is never unsubscribed, for the reason given above.
        AtomicInteger control = new AtomicInteger();
        orchestrator.subscribe("hw2", String.class, s -> control.incrementAndGet());
        // Route it through the same path the binder uses for @OnHardwareThread.
        ((OrchestratorImpl) orchestrator).markSubscriptionAsHardwareThreaded(sub);

        orchestrator.publish("hw2", "before");
        await(hits::get, "the hardware-thread callback must run before removal");
        await(control::get, "the control callback must run before removal");

        sub.unsubscribe();
        int settled = hits.get();
        orchestrator.publish("hw2", "after-unsub");
        await(control::get,
                "the control must observe the post-unsubscribe publish, proving dispatch ran");

        assertEquals(settled, hits.get(),
                "unsubscribe must stop a subscriber that was rerouted to the hardware thread");
        assertTrue(t.latestValue().isPresent(), "publish still records the latest value");
    }

    /**
     * Poll {@code cond} until it is true, failing the test if it never becomes true.
     *
     * <p>Failing on expiry is the point: a silent timeout lets a test that only counts
     * deliveries compare zero against zero and pass without the callback ever running.
     *
     * @param cond the condition to await; it must count real observations so the caller
     *             can assert on them afterwards
     * @param message what the awaited condition was, for the failure output
     */
    private static void await(java.util.function.IntSupplier cond, String message) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsInt() > 0) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for: " + message, e);
            }
        }
        fail("condition never became true within 3s: " + message);
    }
}
