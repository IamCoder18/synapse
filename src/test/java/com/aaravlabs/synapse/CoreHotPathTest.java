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

    private RecordingLogSink logs;
    private Orchestrator orchestrator;

    @BeforeEach void setUp() {
        logs = new RecordingLogSink();
        orchestrator = Orchestrator.create("core-hot-path", logs);
    }
    @AfterEach  void tearDown() { orchestrator.close(); }

    /**
     * Records what the core logged so tests can assert on log output, not just on
     * side effects. Mirrors everything to {@link LogSink#STDERR} so a failing run
     * still shows the orchestrator's trace.
     */
    private static final class RecordingLogSink implements LogSink {
        private final List<String> errors = new CopyOnWriteArrayList<>();

        /** @return every {@code error(...)} logged so far, in order. */
        List<String> errors() { return errors; }

        @Override public void info(String tag, String message) { LogSink.STDERR.info(tag, message); }
        @Override public void warn(String tag, String message) { LogSink.STDERR.warn(tag, message); }
        @Override public void error(String tag, String message) {
            errors.add(message);
            LogSink.STDERR.error(tag, message);
        }
        @Override public void error(String tag, String message, Throwable t) {
            errors.add(message + " (" + t + ")");
            LogSink.STDERR.error(tag, message, t);
        }
    }

    @Test
    void hardwareActionsFacadeIsSharedAcrossCalls() throws Exception {
        assertSame(orchestrator.hardware(), orchestrator.hardware());

        // Cached, not frozen: the facade still reaches the hardware thread.
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        orchestrator.hardware().run(() -> { hits.incrementAndGet(); done.countDown(); });
        assertTrue(done.await(2, TimeUnit.SECONDS), "hardware().run() should have run");
        assertEquals(1, hits.get());
    }

    @Test
    void annotatedSubscriberStillReceivesPrimitiveAndWrapperTypedMessages() throws Exception {
        // Covers both sides of the hoisted normalisation: a primitive parameter and a
        // reference-typed one.
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
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);

        // The topic has to be typed more loosely than the handler parameter for the
        // drop branch to be reachable: publish() rejects a String for a Double topic
        // before any subscriber sees it. Topic types are first-writer-wins, so
        // creating it as Object first is the realistic case — two handlers with
        // different parameter types on one topic.
        orchestrator.getOrCreateTopic("hot/drop", Object.class);

        class N extends Node {
            N(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "hot/drop")
            public void onDouble(double v) { calls.incrementAndGet(); first.countDown(); }
        }
        orchestrator.registerNode("n", new N(orchestrator));

        orchestrator.publish("hot/drop", "text");
        assertFalse(first.await(150, TimeUnit.MILLISECONDS),
                "mismatched message must be dropped, not delivered");
        assertEquals(0, calls.get(), "mismatched message must be dropped, not delivered");
        // "Not delivered" alone cannot tell a silent drop apart from a delivery
        // that threw and was swallowed by the binder's catch (Throwable): if the
        // isInstance guard were removed, m.invoke(node, "text") on a double
        // parameter raises IllegalArgumentException, the handler never runs, and
        // the two assertions above still hold. A dropped message leaves no trace;
        // a failed delivery is logged as an error, so that is what we assert on.
        assertEquals(List.of(), logs.errors(),
                "mismatched message must be dropped silently, not delivered and failed");

        // A real Double still gets through.
        orchestrator.publish("hot/drop", 1.5);
        assertTrue(first.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals(List.of(), logs.errors(), "matching message must not log an error");
    }

    @Test
    void bulkReadCallbackGetsAWorkingView() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        com.aaravlabs.synapse.ftc.HardwareActions.BulkReadHandle handle =
                orchestrator.hardware().bulkRead(200, view -> {
                    view.publish("hot/bulk", 1.0f);
                    done.countDown();
                });
        try {
            assertTrue(done.await(2, TimeUnit.SECONDS), "bulk read callback should have run");
            assertEquals(Float.valueOf(1.0f),
                    orchestrator.getLatestValue("hot/bulk", Float.class).orElse(null));
        } finally {
            handle.cancel();
        }
    }
}
