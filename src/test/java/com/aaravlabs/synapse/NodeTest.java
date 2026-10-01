package com.aaravlabs.synapse;

import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NodeTest {

    private Orchestrator orchestrator;

    @BeforeEach void setUp() { orchestrator = Orchestrator.create("test"); }
    @AfterEach  void tearDown() { orchestrator.close(); }

    /**
     * Polling cadence for {@link #awaitQuiescence(AtomicInteger)}. Fine enough
     * that a tick landing mid-wait is always noticed.
     */
    private static final long QUIESCE_POLL_MS = 1;

    /**
     * How long the counter must hold still before it is treated as final.
     * The loop runs at 100 Hz, so this spans 15 periods: a loop that is still
     * ticking cannot look still for that long, while an in-flight tick -- which
     * is a single {@code AtomicInteger} increment, sometimes a scheduler thread
     * that lost its core mid-dispatch -- settles far sooner.
     */
    private static final long QUIESCE_HOLD_MS = 150;

    /** Upper bound on the quiescence wait before declaring the loop still alive. */
    private static final long QUIESCE_TIMEOUT_MS = 5000;

    /** Window over which a stopped loop must not tick again. 20 periods at 100 Hz. */
    private static final long OBSERVE_MS = 200;

    static class SubscriberNode extends Node {
        // Written on a callback-pool thread, read and asserted on from the test
        // thread, so it has to be a concurrent structure. A plain ArrayList
        // gives the reader no happens-before edge: an element the writer has
        // already added is not guaranteed to be visible, and a reader can see a
        // grown size without seeing the element it accounts for. Every other
        // cross-thread recorder in this suite uses CopyOnWriteArrayList for
        // exactly this reason.
        final List<String> events = new CopyOnWriteArrayList<>();
        SubscriberNode(Orchestrator o) { super(o); }
        @SubscribedTo(topic = "ping")
        public void onPing(String msg) { events.add(msg); }
    }

    static class PeriodicNode extends Node {
        final AtomicInteger ticks = new AtomicInteger();
        PeriodicNode(Orchestrator o) { super(o); }
        @RunPeriodically(hz = 100)
        public void tick() { ticks.incrementAndGet(); }
    }

    @Test
    void subscribedTo_isInvokedOnPublish() throws Exception {
        SubscriberNode n = new SubscriberNode(orchestrator);
        orchestrator.getOrCreateTopic("ping", String.class);
        orchestrator.registerNode("sub", n);

        orchestrator.publish("ping", "hello");
        for (int i = 0; i < 50 && n.events.isEmpty(); i++) Thread.sleep(10);
        assertEquals(List.of("hello"), n.events);
    }

    @Test
    void runPeriodically_firesAtApproximateRate() throws Exception {
        PeriodicNode n = new PeriodicNode(orchestrator);
        orchestrator.registerNode("periodic", n);

        Thread.sleep(120); // ~12 ticks at 100 Hz
        int after = n.ticks.get();
        assertTrue(after >= 8 && after <= 20,
                "expected ~12 ticks in 120ms at 100Hz, got " + after);
    }

    @Test
    void unregisterNode_stopsSubscriptionsAndPeriodic() throws Exception {
        SubscriberNode n = new SubscriberNode(orchestrator);
        PeriodicNode p = new PeriodicNode(orchestrator);
        orchestrator.getOrCreateTopic("ping", String.class);
        orchestrator.registerNode("sub", n);
        orchestrator.registerNode("periodic", p);

        orchestrator.unregisterNode("sub");
        orchestrator.publish("ping", "after-unregister");
        Thread.sleep(50);
        assertTrue(n.events.isEmpty(), "should not receive after unregister");

        orchestrator.unregisterNode("periodic");

        // The baseline is taken here, *after* the unregister, and only once the
        // counter has stopped moving -- not before the unregister, as this test
        // used to do.
        //
        // unregisterNode does not drain the loop; it calls cancel(false) on the
        // ScheduledFuture, which prevents any *further* execution but lets an
        // invocation that is already running on a scheduler thread finish. That
        // in-flight tick still increments the counter, and it can land at any
        // point in the gap between a scheduler thread picking the task up and
        // the reflectively-invoked method returning -- unbounded in principle,
        // milliseconds in practice when the machine is loaded. Sampling `before`
        // ahead of the unregister therefore opened a window that *straddled* the
        // cancellation, and any tick the library was still entitled to run inside
        // it turned into a spurious failure: this assertion is about the loop
        // being cancelled, and it was failing on the one tick cancellation
        // permits.
        //
        // Waiting for quiescence first makes the baseline the counter's final
        // value, so the equality below is exact -- and it doubles as a check in
        // its own right, because a loop that genuinely kept ticking could never
        // hold still for QUIESCE_HOLD_MS and would fail in awaitQuiescence.
        int baseline = awaitQuiescence(p.ticks);

        Thread.sleep(OBSERVE_MS);

        assertEquals(baseline, p.ticks.get(), "periodic should stop after unregister");
    }

    /**
     * Wait until {@code counter} stops moving and return its value once it has.
     *
     * <p>Fails the test if it is still moving when the timeout expires, which is
     * the outcome when a periodic loop is not actually cancelled.
     */
    private static int awaitQuiescence(AtomicInteger counter) throws InterruptedException {
        long holdNanos = TimeUnit.MILLISECONDS.toNanos(QUIESCE_HOLD_MS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUIESCE_TIMEOUT_MS);

        int last = counter.get();
        long stillSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(QUIESCE_POLL_MS);
            int now = counter.get();
            if (now != last) {
                // An in-flight tick (or, if cancellation is broken, a live loop)
                // just moved. Restart the quiet period from here.
                last = now;
                stillSince = System.nanoTime();
            } else if (System.nanoTime() - stillSince >= holdNanos) {
                return now;
            }
        }
        return fail("counter never stopped moving; the periodic node is still ticking after"
                + " unregisterNode (at " + counter.get() + " ticks)", null);
    }

    @Test
    void multipleSubscribedToOnSameMethod() throws Exception {
        orchestrator.getOrCreateTopic("a", String.class);
        orchestrator.getOrCreateTopic("b", String.class);

        // Concurrent for the same reason SubscriberNode.events is: these
        // handlers run on the callback pool while the assertions below read the
        // list from the test thread.
        class N extends Node {
            final List<String> seen = new CopyOnWriteArrayList<>();
            N(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "a")
            @SubscribedTo(topic = "b")
            public void onAny(String msg) { seen.add(msg); }
        }

        N n = new N(orchestrator);
        orchestrator.registerNode("n", n);

        orchestrator.publish("a", "1");
        orchestrator.publish("b", "2");
        for (int i = 0; i < 50 && n.seen.size() < 2; i++) Thread.sleep(10);
        assertTrue(n.seen.contains("1"));
        assertTrue(n.seen.contains("2"));
    }
}
