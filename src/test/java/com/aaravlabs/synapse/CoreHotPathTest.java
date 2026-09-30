package com.aaravlabs.synapse;

import com.aaravlabs.synapse.annotation.SubscribedTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the core hot-path cleanups: the shared {@code HardwareActions} facade and the
 * per-message reflection work hoisted out of the annotation binder's dispatch lambda.
 */
class CoreHotPathTest {

    private Orchestrator orchestrator;

    @BeforeEach void setUp() { orchestrator = Orchestrator.create("core-hot-path"); }
    @AfterEach  void tearDown() { orchestrator.close(); }

    @Test
    void hardwareActionsFacadeIsSharedAcrossCalls() throws Exception {
        // Hot subscribers call orchestrator.hardware() once per message; it used to
        // allocate a fresh facade each time. Same instance every call now, so it is
        // safe to capture and reuse.
        assertSame(orchestrator.hardware(), orchestrator.hardware());

        // And it must still work, not just be cached. run() is async, so wait on a
        // latch rather than asserting immediately.
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        orchestrator.hardware().run(() -> { hits.incrementAndGet(); done.countDown(); });
        assertTrue(done.await(2, TimeUnit.SECONDS), "hardware().run() should have run");
        assertEquals(1, hits.get());
    }

    @Test
    void annotatedSubscriberStillReceivesPrimitiveAndWrapperTypedMessages() throws Exception {
        // The binder's per-message type check moved from
        // `paramType.isPrimitive() ? boxed(paramType).isInstance(msg) : paramType.isInstance(msg)`
        // to a single pre-normalised `effectiveParam.isInstance(msg)`. That is only
        // equivalent if `boxed` leaves non-primitives alone — so cover both a primitive
        // parameter and a reference-typed one.
        List<Double> doubles = new CopyOnWriteArrayList<>();
        List<String> strings = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        class N extends Node {
            N(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "hot/double")
            public void onDouble(double v) { doubles.add(v); done.countDown(); }
            @SubscribedTo(topic = "hot/string")
            public void onString(String v) { strings.add(v); done.countDown(); }
        }
        orchestrator.registerNode("n", new N(orchestrator));

        orchestrator.publish("hot/double", 0.25);
        orchestrator.publish("hot/string", "go");

        assertTrue(done.await(2, TimeUnit.SECONDS), "both handlers should run");
        assertEquals(List.of(0.25), doubles);
        assertEquals(List.of("go"), strings);
    }

    @Test
    void annotatedSubscriberSilentlyDropsMismatchedMessages() throws Exception {
        // Behaviour that must be preserved: a message whose runtime type does not match
        // the handler parameter is silently dropped — the handler does not run and
        // nothing is logged. This is the branch the hoisted check guards.
        //
        // The topic has to be typed more loosely than the handler parameter for the
        // branch to be reachable at all: publish() rejects a String for a Double topic
        // with IllegalArgumentException before any subscriber sees it. So the topic is
        // created as Object first (topic types are first-writer-wins), which is the
        // realistic case — two handlers with different parameter types on one topic.
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        orchestrator.getOrCreateTopic("hot/drop", Object.class);

        class N extends Node {
            N(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "hot/drop")
            public void onDouble(double v) { calls.incrementAndGet(); first.countDown(); }
        }
        orchestrator.registerNode("n", new N(orchestrator));

        // "text" is not a Double, so the handler must not run for it.
        orchestrator.publish("hot/drop", "text");
        Thread.sleep(150);
        assertEquals(0, calls.get(), "mismatched message must be dropped, not delivered");

        // A real Double still gets through.
        orchestrator.publish("hot/drop", 1.5);
        assertTrue(first.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void bulkReadCallbackGetsAWorkingView() throws Exception {
        // The HardwareView handed to bulkRead callbacks is now the shared instance.
        CountDownLatch done = new CountDownLatch(1);
        List<Float> seen = new CopyOnWriteArrayList<>();
        com.aaravlabs.synapse.ftc.HardwareActions.BulkReadHandle handle =
                orchestrator.hardware().bulkRead(200, view -> {
                    view.publish("hot/bulk", 1.0f);
                    seen.add(1.0f);
                    done.countDown();
                });
        try {
            assertTrue(done.await(2, TimeUnit.SECONDS), "bulk read callback should have run");
            assertEquals(1, orchestrator.getLatestValue("hot/bulk", Float.class).orElse(null));
        } finally {
            handle.cancel();
        }
        assertFalse(seen.isEmpty());
    }
}
