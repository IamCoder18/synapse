package com.aaravlabs.synapse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TopicTest {

    /** Async callback tests poll every 10 ms for up to 5 s; CI runners can be slow to schedule callback threads. */
    private static final int POLL_INTERVAL_MS = 10;
    private static final int AWAIT_BUDGET_MS = 5000;

    /** Waits until {@code cond} is true or the budget elapses. */
    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MS;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_INTERVAL_MS);
    }

    private Orchestrator orchestrator;

    @BeforeEach void setUp() { orchestrator = Orchestrator.create("test"); }
    @AfterEach  void tearDown() { orchestrator.close(); }

    @Test
    void getOrCreate_returnsSameTopic() {
        Topic<String> a = orchestrator.getOrCreateTopic("t", String.class);
        Topic<String> b = orchestrator.getOrCreateTopic("t", String.class);
        assertSame(a, b);
    }

    @Test
    void getOrCreate_throwsOnTypeMismatch() {
        orchestrator.getOrCreateTopic("t", String.class);
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.getOrCreateTopic("t", Integer.class));
    }

    @Test
    void latestValue_isEmptyBeforeAnyPublish() {
        Topic<String> t = orchestrator.getOrCreateTopic("t", String.class);
        assertTrue(t.latestValue().isEmpty());
        assertNull(t.latestValueOr(null));
    }

    @Test
    void latestValue_updatesAfterPublish() {
        orchestrator.getOrCreateTopic("t", String.class);
        orchestrator.publish("t", "hello");
        assertEquals(Optional.of("hello"), orchestrator.getLatestValue("t", String.class));
        orchestrator.publish("t", "world");
        assertEquals(Optional.of("world"), orchestrator.getLatestValue("t", String.class));
    }

    @Test
    void publish_throwsOnNullValue() {
        orchestrator.getOrCreateTopic("t", String.class);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.publish("t", null));
    }

    @Test
    void publish_autoCreatesTopic() {
        // Publishers don't need to pre-register topics; the topic is created
        // on first publish with the value's runtime type.
        assertEquals(Optional.empty(), orchestrator.findTopic("late"));
        orchestrator.publish("late", "hello");
        assertTrue(orchestrator.findTopic("late").isPresent());
        assertEquals(Optional.of("hello"), orchestrator.getLatestValue("late", String.class));
    }

    @Test
    void publish_throwsOnTypeMismatch() {
        orchestrator.getOrCreateTopic("t", String.class);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.publish("t", 42));
    }

    @Test
    void primitiveAndWrapperTypesAreEquivalent() {
        // The library normalizes primitive vs wrapper so an annotation-bound
        // method with `double` and a programmatic topic created with `Double`
        // resolve to the same topic.
        Topic<Double> a = orchestrator.getOrCreateTopic("t", Double.class);
        Topic<Double> b = orchestrator.getOrCreateTopic("t", double.class);
        assertSame(a, b);

        orchestrator.publish("t", 0.5);
        assertEquals(Optional.of(0.5), orchestrator.getLatestValue("t", double.class));
        assertEquals(Optional.of(0.5), orchestrator.getLatestValue("t", Double.class));
    }

    @Test
    void subscribe_receivesPublishedValues() throws Exception {
        orchestrator.getOrCreateTopic("t", String.class);
        // Subscriber callbacks are dispatched to the callback pool
        // (ThreadPoolExecutor, core 4 / max 16), so they run concurrently on a
        // different thread than this one: the collection has to be safe for
        // that, and delivery order is not part of the contract. Asserting a
        // fixed order here is what made this test flaky -- publish "a" then
        // "b" and the two callbacks are free to run in either order.
        List<String> received = new CopyOnWriteArrayList<>();
        orchestrator.subscribe("t", String.class, received::add);

        orchestrator.publish("t", "a");
        orchestrator.publish("t", "b");

        // Callbacks are async; wait briefly.
        await(() -> received.size() >= 2);
        assertEquals(2, received.size(), "both published values must be delivered");
        assertEquals(Set.of("a", "b"), new HashSet<>(received),
                "each published value must be delivered exactly once");
    }

    @Test
    void subscribe_unsubscribe_stopsReceiving() throws Exception {
        orchestrator.getOrCreateTopic("t", String.class);
        List<String> received = new CopyOnWriteArrayList<>();
        Subscription sub = orchestrator.subscribe("t", String.class, received::add);

        orchestrator.publish("t", "a");
        await(() -> received.size() >= 1);
        assertEquals(1, received.size());

        sub.unsubscribe();
        orchestrator.publish("t", "b");
        Thread.sleep(50);
        assertEquals(1, received.size(), "should not receive after unsubscribe");
    }
}
