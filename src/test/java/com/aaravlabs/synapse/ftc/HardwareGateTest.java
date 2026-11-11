package com.aaravlabs.synapse.ftc;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link HardwareGate} contract: labels and the label hierarchy
 * (including tilde escapes), restrictions (DROP / QUEUE), the ALL and UNLABELLED
 * roots, multi-label fate, child policies (sealed children), exceptions
 * (carve-outs), slot coalescing, periodic-tick skipping, and the "an action runs,
 * or its caller finds out why it didn't" error story.
 */
class HardwareGateTest {

    /** Records log lines so tests can assert on warnings and summaries. */
    static final class RecordingSink implements LogSink {
        final List<String> warns = new CopyOnWriteArrayList<>();
        final List<String> infos = new CopyOnWriteArrayList<>();
        @Override public void info(String tag, String message) { infos.add(message); }
        @Override public void warn(String tag, String message) { warns.add(message); }
        @Override public void error(String tag, String message) { }
        @Override public void error(String tag, String message, Throwable t) { }
        String warnsJoined() { return String.join("\n", warns); }
    }

    private RecordingSink sink;
    private Orchestrator orchestrator;
    private HardwareActions hw;

    @BeforeEach void setUp() {
        sink = new RecordingSink();
        orchestrator = Orchestrator.create("gate", sink);
        hw = orchestrator.hardware();
    }
    @AfterEach void tearDown() { orchestrator.close(); }

    /** Fence the hardware thread: runs after everything dispatched before it. */
    private void fence() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        orchestrator.runOnHardwareThread(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS), "hardware thread should drain");
    }

    /** Fence via a label, for tests where unlabelled work is restricted. */
    private void fence(String label) throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        hw.run(label, drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS), "hardware thread should drain");
    }

    /** Assert the future failed with a {@link HardwareActionRejectedException}; return it. */
    private static HardwareActionRejectedException assertRejected(CompletableFuture<?> f) {
        java.util.concurrent.ExecutionException e = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertInstanceOf(HardwareActionRejectedException.class, e.getCause(),
                "expected a gate rejection, got: " + e.getCause());
        return (HardwareActionRejectedException) e.getCause();
    }

    // ------------------------------------------------------------------
    // label paths: hierarchy and tilde escapes
    // ------------------------------------------------------------------

    @Test
    void tildeEscapes_writePartIsOneSegmentNotUnderWrite() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        hw.restrict("write", GateMode.DROP);

        // 'write~1part' decodes to the single segment 'write/part': NOT under 'write'.
        hw.run("write~1part", ran::incrementAndGet);
        fence();
        assertEquals(1, ran.get(), "a literal 'write/part' segment must not be caught by 'write'");

        // But a real two-segment 'write/part' is under 'write'.
        hw.run("write/part", ran::incrementAndGet);
        fence();
        assertEquals(1, ran.get(), "'write/part' as two segments IS under 'write'");
    }

    @Test
    void tildeEscapes_01IsLiteralTildeOne() throws Exception {
        // '~01' decodes (left to right) to the literal '~1', not to '/'.
        hw.restrict("~01", GateMode.DROP);
        AtomicInteger ran = new AtomicInteger();
        hw.run("~01", ran::incrementAndGet);           // matches exactly
        hw.run("~1", ran::incrementAndGet);            // the literal '/' segment — no match
        fence();
        assertEquals(1, ran.get());
    }

    @Test
    void malformedLabels_failFastAtSubmission_andAreRefusedByGateOps() {
        assertThrows(IllegalArgumentException.class, () -> hw.run("write~", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run("write~2", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run("a//b", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run("/a", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run("a/", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run("", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.labels("good", "bad~"));
        // The reserved roots cannot label actions.
        assertThrows(IllegalArgumentException.class, () -> hw.run(HardwareGate.ALL, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> hw.run(HardwareGate.UNLABELLED, () -> { }));
        // Gate operations refuse malformed input instead of throwing.
        assertFalse(hw.restrict("bad~", GateMode.DROP));
        assertFalse(hw.release("a//b"));
        assertFalse(sink.warns.isEmpty(), "refusals must be visible in the log");
    }

    // ------------------------------------------------------------------
    // restrictions: coverage, modes, fate resolution
    // ------------------------------------------------------------------

    @Test
    void restrictCoversSublabels_butOnlyDownward() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch held = new CountDownLatch(2);
        hw.restrict("write", GateMode.QUEUE);

        hw.run("write", held::countDown);
        hw.run("write/drive/left", held::countDown);
        hw.run("writex", ran::incrementAndGet);
        hw.run("other", ran::incrementAndGet);
        fence();
        assertEquals(2, ran.get(), "sibling-ish and unrelated labels must run");
        assertEquals(2, held.getCount(), "the label and its sublabels must be held");

        hw.release("write");
        assertTrue(held.await(2, TimeUnit.SECONDS), "held work runs on release");
    }

    @Test
    void heldWorkRunsInSubmissionOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        hw.restrict("seq", GateMode.QUEUE);
        for (int i = 0; i < 5; i++) {
            final int n = i;
            hw.run("seq", () -> order.add("a" + n));
        }
        fence();
        hw.release("seq");
        fence();
        assertEquals(List.of("a0", "a1", "a2", "a3", "a4"), order,
                "held work drains in submission order");
    }

    @Test
    void dropMode_dropsRunFailsFutureAndThrowsForCall() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        hw.restrict("write", GateMode.DROP);

        hw.run("write", ran::incrementAndGet);
        fence();
        assertEquals(0, ran.get(), "dropped fire-and-forget must never run");

        CompletableFuture<String> f = hw.callAsync("write", () -> "never");
        HardwareActionRejectedException fromFuture = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, fromFuture.reason());

        HardwareActionRejectedException thrown = assertThrows(
                HardwareActionRejectedException.class, () -> hw.call("write", () -> "never"));
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, thrown.reason());

        // Unlabelled work is untouched by a named restriction.
        assertEquals("ok", hw.call(() -> "ok"));
    }

    @Test
    void blockingCallFailsFastBehindQueueHold_withGuidance() {
        hw.restrict("write", GateMode.QUEUE);
        long t0 = System.nanoTime();
        HardwareActionRejectedException thrown = assertThrows(
                HardwareActionRejectedException.class, () -> hw.call("write", () -> "never"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, thrown.reason());
        assertTrue(elapsedMs < 1_000, "call must fail fast, not wait for a release");
        assertTrue(thrown.getMessage().contains("callAsync"),
                "the error should say what to do instead: " + thrown.getMessage());
    }

    @Test
    void multiLabel_strictestRestrictionWins() {
        hw.restrict("m1", GateMode.QUEUE);
        hw.restrict("m2", GateMode.DROP);
        CompletableFuture<String> f =
                hw.labels("m1", "m2").callAsync(() -> "never");
        HardwareActionRejectedException e = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, e.reason(),
                "DROP must beat QUEUE when both cover a multi-label action");
    }

    @Test
    void multiLabel_heldUntilEveryLabelIsFree() throws Exception {
        hw.restrict("h1", GateMode.QUEUE);
        hw.restrict("h2", GateMode.QUEUE);
        CompletableFuture<String> f = hw.labels("h1", "h2").callAsync(() -> "done");

        hw.release("h1");
        assertFalse(f.isDone(), "still covered by h2");
        hw.release("h2");
        assertEquals("done", f.get(2, TimeUnit.SECONDS), "runs once every label is free");
    }

    // ------------------------------------------------------------------
    // ALL and UNLABELLED
    // ------------------------------------------------------------------

    @Test
    void allRestrictionCoversUnlabelledWorkToo() throws Exception {
        hw.restrict("write", GateMode.DROP);
        AtomicInteger unlabelled = new AtomicInteger();
        hw.run(unlabelled::incrementAndGet);
        fence();
        assertEquals(1, unlabelled.get(), "a named restriction must not reach unlabelled work");

        hw.restrict(HardwareGate.ALL, GateMode.QUEUE);
        hw.run(unlabelled::incrementAndGet);
        hw.run("anything/else", unlabelled::incrementAndGet);
        Thread.sleep(150);
        assertEquals(1, unlabelled.get(), "ALL must hold every hardware action");

        hw.release(HardwareGate.ALL);
        fence();
        assertEquals(3, unlabelled.get(), "everything runs once ALL lifts");
    }

    @Test
    void unlabelledRestrictionCoversOnlyUnlabelledWork() throws Exception {
        AtomicInteger n = new AtomicInteger();
        hw.restrict(HardwareGate.UNLABELLED, GateMode.DROP);
        hw.run(n::incrementAndGet);
        hw.run("named", n::incrementAndGet);
        fence("named-fence");
        assertEquals(1, n.get(), "only the unlabelled action is dropped");
    }

    // ------------------------------------------------------------------
    // sealed children
    // ------------------------------------------------------------------

    @Test
    void sealedChildren_refuseGateOpsOnCoveredDescendants() {
        assertTrue(hw.restrict("shooter").queue().sealChildren().apply());

        assertFalse(hw.restrict("shooter/firing", GateMode.DROP),
                "a covered child may not be restricted under a seal");
        assertFalse(hw.release("shooter/firing"),
                "a covered child may not be released under a seal");
        assertTrue(hw.restrict("elsewhere", GateMode.DROP), "unrelated labels are fine");

        assertTrue(hw.release("shooter"), "the seal's own node is always releasable");
        assertTrue(hw.restrict("shooter/firing", GateMode.DROP),
                "children are gateable again once the seal lifts");
    }

    @Test
    void sealedSuspendsChildRestrictions_untilTheSealLifts() throws Exception {
        assertTrue(hw.restrict("shooter/drive", GateMode.DROP));
        assertTrue(hw.restrict("shooter").queue().sealChildren().apply());

        CompletableFuture<String> f = hw.labels("shooter/drive").callAsync(() -> "ran");
        Thread.sleep(150);
        assertFalse(f.isDone(),
                "the suspended child DROP must not act; the seal alone decides (hold)");

        // The seal lifts: the suspended DROP re-applies and drops the still-held work.
        assertTrue(hw.release("shooter"));
        HardwareActionRejectedException e = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, e.reason());

        // And future work under the child restriction is dropped again.
        assertRejected(hw.labels("shooter/drive").callAsync(() -> "ran"));
    }

    @Test
    void exceptedSubtreeIsOutsideTheSeal_andStaysGateable() throws Exception {
        assertTrue(hw.restrict("shooter").queue().sealChildren()
                .except("shooter/readonly").apply());

        // The carve-out is independently gateable despite the seal.
        assertTrue(hw.restrict("shooter/readonly", GateMode.DROP));
        AtomicInteger n = new AtomicInteger();
        hw.run("shooter/readonly", n::incrementAndGet);
        fence();
        assertEquals(0, n.get(), "its own restriction drops it");
        assertTrue(hw.release("shooter/readonly"));

        hw.run("shooter/readonly", n::incrementAndGet);
        fence();
        assertEquals(1, n.get(), "carved-out work runs free of the sealed restriction");

        // Covered children still obey the seal.
        CompletableFuture<String> f = hw.labels("shooter/firing").callAsync(() -> "ran");
        assertFalse(f.isDone());
        hw.release("shooter");
        assertEquals("ran", f.get(2, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------
    // exceptions
    // ------------------------------------------------------------------

    @Test
    void exceptionsCarveOutSubtrees() throws Exception {
        assertTrue(hw.restrict("shooter").drop().except("shooter/write").apply());
        AtomicInteger n = new AtomicInteger();
        hw.run("shooter/write", n::incrementAndGet);
        hw.run("shooter/write/power", n::incrementAndGet);
        fence();
        assertEquals(2, n.get(), "the carve-out covers the label and its sublabels");

        hw.run("shooter", n::incrementAndGet);
        hw.run("shooter/fire", n::incrementAndGet);
        fence();
        assertEquals(2, n.get(), "everything else under the restriction is dropped");
    }

    @Test
    void exceptionsAreLocalToTheirOwnRestriction() throws Exception {
        assertTrue(hw.restrict("s2").queue().except("s2/x").apply());
        assertTrue(hw.restrict("s2/x", GateMode.DROP));

        CompletableFuture<String> f = hw.labels("s2/x").callAsync(() -> "ran");
        HardwareActionRejectedException e = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, e.reason(),
                "carving out of one restriction must not shield from another");

        CompletableFuture<String> g = hw.labels("s2/y").callAsync(() -> "ran");
        assertFalse(g.isDone(), "s2/y is still held by the s2 restriction");
    }

    @Test
    void exceptionsOutsideTheSubtreeAreRefused() throws Exception {
        assertFalse(hw.restrict("s3").drop().except("elsewhere").apply(),
                "an exception outside the covered subtree must refuse the whole restriction");
        assertFalse(hw.restrict("s4").drop().except(HardwareGate.UNLABELLED).apply());
        AtomicInteger n = new AtomicInteger();
        hw.run("s3", n::incrementAndGet);
        fence();
        assertEquals(1, n.get(), "a refused restriction must not be applied");

        // On ALL, anything goes — including carving out unlabelled work.
        assertTrue(hw.restrict(HardwareGate.ALL).queue().except(HardwareGate.UNLABELLED).apply());
        hw.run(n::incrementAndGet);
        fence();
        assertEquals(2, n.get(), "unlabelled work flows through the carve-out");
    }

    // ------------------------------------------------------------------
    // retroactive re-evaluation
    // ------------------------------------------------------------------

    @Test
    void heldWorkIsDroppedRetroactivelyWhenDroppingRestrictionArrives() {
        hw.restrict("r1", GateMode.QUEUE);
        CompletableFuture<String> f = hw.labels("r1").callAsync(() -> "ran");
        assertFalse(f.isDone());

        hw.restrict(HardwareGate.ALL, GateMode.DROP);
        HardwareActionRejectedException e = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, e.reason(),
                "DROP means never: a later DROP restriction must drop held work too");
    }

    @Test
    void reRestrictingReplacesTheWholeSpec() {
        assertTrue(hw.restrict("rr", GateMode.QUEUE));
        CompletableFuture<String> f = hw.labels("rr").callAsync(() -> "ran");
        assertFalse(f.isDone());

        assertTrue(hw.restrict("rr", GateMode.DROP), "last call wins");
        HardwareActionRejectedException e = assertRejected(f);
        assertEquals(HardwareActionRejectedException.Reason.DROPPED_BY_GATE, e.reason(),
                "the held action is re-evaluated against the replaced spec");
    }

    // ------------------------------------------------------------------
    // slots
    // ------------------------------------------------------------------

    @Test
    void slotCoalescesHeldWorkToTheLatestAction() throws Exception {
        List<Integer> ran = new CopyOnWriteArrayList<>();
        hw.restrict("q", GateMode.QUEUE);

        CompletableFuture<String> first =
                hw.labels("q").slot("setpoint").callAsync(() -> { ran.add(1); return "1"; });
        CompletableFuture<String> second =
                hw.labels("q").slot("setpoint").callAsync(() -> { ran.add(2); return "2"; });
        CompletableFuture<String> third =
                hw.labels("q").slot("setpoint").callAsync(() -> { ran.add(3); return "3"; });

        HardwareActionRejectedException e1 = assertRejected(first);
        HardwareActionRejectedException e2 = assertRejected(second);
        assertEquals(HardwareActionRejectedException.Reason.SUPERSEDED, e1.reason());
        assertEquals(HardwareActionRejectedException.Reason.SUPERSEDED, e2.reason());

        hw.release("q");
        assertEquals("3", third.get(2, TimeUnit.SECONDS));
        fence();
        assertEquals(List.of(3), ran, "only the most recent action per slot is used");
    }

    @Test
    void slotSupersedesWorkAlreadyQueuedOnTheHardwareThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        orchestrator.runOnHardwareThread(() -> {
            blockerStarted.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        AtomicInteger ran = new AtomicInteger();
        CompletableFuture<String> first =
                hw.slot("s2").callAsync(() -> { ran.incrementAndGet(); return "first"; });
        hw.slot("s2").run(() -> ran.incrementAndGet());
        HardwareActionRejectedException e = assertRejected(first);
        assertEquals(HardwareActionRejectedException.Reason.SUPERSEDED, e.reason());

        release.countDown();
        fence();
        assertEquals(1, ran.get(), "the superseded action must be skipped wherever it waited");
    }

    @Test
    void slotDoesNotSupersedeAnActionThatAlreadyStarted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        hw.slot("s3").run(() -> {
            ran.add("first");
            started.countDown();
            try { unblock.await(); } catch (InterruptedException ignored) { }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        hw.slot("s3").run(() -> ran.add("second"));
        unblock.countDown();
        fence();
        assertEquals(List.of("first", "second"), ran,
                "a running action cannot be superseded; the new one runs after it");
    }

    // ------------------------------------------------------------------
    // periodic work: ticks skip, never queue
    // ------------------------------------------------------------------

    @Test
    void periodicTicksAreSkippedWhileRestricted_andResumeOnRelease() throws Exception {
        class TickNode extends Node {
            final AtomicInteger ticks = new AtomicInteger();
            TickNode(Orchestrator o) { super(o); }
            @RunPeriodically(hz = 50, hardware = true, labels = "pt")
            void tick() { ticks.incrementAndGet(); }
        }
        TickNode node = new TickNode(orchestrator);
        orchestrator.registerNode("ticks", node);
        awaitTrue(() -> node.ticks.get() > 5, "ticks should start immediately");

        hw.restrict("pt", GateMode.QUEUE);
        Thread.sleep(100);
        int frozen = node.ticks.get();
        Thread.sleep(200);
        assertEquals(frozen, node.ticks.get(), "ticks must skip while restricted (QUEUE)");

        hw.release("pt");
        awaitTrue(() -> node.ticks.get() > frozen, "ticks resume on release");
    }

    @Test
    void bulkReadTicksAreSkippedWhileRestricted() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        HardwareActions.BulkReadHandle handle =
                hw.labels("br").bulkRead(50, view -> reads.incrementAndGet());
        awaitTrue(() -> reads.get() > 3, "bulk read should start immediately");

        hw.restrict("br", GateMode.DROP);
        Thread.sleep(100);
        int frozen = reads.get();
        Thread.sleep(200);
        assertEquals(frozen, reads.get(), "bulk-read ticks skip under DROP too");

        hw.release("br");
        awaitTrue(() -> reads.get() > frozen, "bulk reads resume on release");
        hw.stopBulkRead(handle);
    }

    // ------------------------------------------------------------------
    // annotations carry labels and slots
    // ------------------------------------------------------------------

    @Test
    void annotatedSubscriberDispatchRespectsLabelsAndSlot() throws Exception {
        class SubNode extends Node {
            final List<String> seen = new CopyOnWriteArrayList<>();
            SubNode(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "gate/in")
            @OnHardwareThread(labels = "sub", slot = "subslot")
            void onMsg(String s) { seen.add(s); }
        }
        SubNode node = new SubNode(orchestrator);
        orchestrator.getOrCreateTopic("gate/in", String.class);
        orchestrator.registerNode("sub", node);

        hw.restrict("sub", GateMode.QUEUE);
        orchestrator.publish("gate/in", "a");
        Thread.sleep(150);
        orchestrator.publish("gate/in", "b");
        Thread.sleep(150);
        assertTrue(node.seen.isEmpty(), "dispatches are held while their label is restricted");

        hw.release("sub");
        Thread.sleep(250);
        assertEquals(List.of("b"), node.seen,
                "slot coalescing keeps only the newest undelivered dispatch");
    }

    @Test
    void malformedAnnotationLabelsFailAtRegistration() {
        class BadNode extends Node {
            BadNode(Orchestrator o) { super(o); }
            @SubscribedTo(topic = "bad")
            @OnHardwareThread(labels = "bad~")
            void onMsg(String s) { }
        }
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.registerNode("bad", new BadNode(orchestrator)));
    }

    // ------------------------------------------------------------------
    // bookkeeping
    // ------------------------------------------------------------------

    @Test
    void releaseWithoutRestrictionReturnsFalse() {
        assertFalse(hw.release("nothing-here"));
        assertTrue(hw.restrict("something", GateMode.DROP));
        assertTrue(hw.release("something"));
    }

    @Test
    void closedOrchestrator_failsFuturesAndCallsImmediately() throws Exception {
        orchestrator.close();
        CompletableFuture<String> f = hw.callAsync(() -> "never");
        java.util.concurrent.ExecutionException e = assertThrows(
                java.util.concurrent.ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause(),
                "callAsync on a closed orchestrator must fail its future, not hang");
        assertThrows(IllegalStateException.class, () -> hw.call(() -> "never"),
                "call on a closed orchestrator must fail fast, not hang");
        hw.run(() -> { }); // fire-and-forget is ignored, not fatal
    }

    @Test
    void dropSummaryIsWarnedOnRelease() throws Exception {
        hw.restrict("ws", GateMode.DROP);
        hw.run("ws", () -> { });
        hw.run("ws", () -> { });
        hw.run("ws", () -> { });
        fence();
        hw.release("ws");
        assertTrue(sink.warnsJoined().contains("3"),
                "release must summarize the drops; got: " + sink.warnsJoined());
    }

    @Test
    void isRestrictedReflectsCoverage() {
        assertFalse(hw.isRestricted("write"));
        hw.restrict("write", GateMode.QUEUE);
        assertTrue(hw.isRestricted("write"));
        assertTrue(hw.isRestricted("write/drive"), "sublabels are covered");
        assertFalse(hw.isRestricted("writex"));
        assertFalse(hw.isRestricted(HardwareGate.ALL), "one label is not everything");
        hw.release("write");
        assertFalse(hw.isRestricted("write"));

        hw.restrict(HardwareGate.ALL, GateMode.DROP);
        assertTrue(hw.isRestricted(HardwareGate.ALL));
        assertTrue(hw.isRestricted("anything"));
    }

    // ------------------------------------------------------------------

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String why)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail(why);
    }
}
