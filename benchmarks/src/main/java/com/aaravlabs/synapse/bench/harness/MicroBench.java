package com.aaravlabs.synapse.bench.harness;

import com.aaravlabs.synapse.LogSink;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Orchestrator;
import com.aaravlabs.synapse.OrchestratorImpl;
import com.aaravlabs.synapse.Subscription;
import com.aaravlabs.synapse.Topic;
import com.aaravlabs.synapse.annotation.SubscribedTo;
import com.aaravlabs.synapse.bench.shared.Blackhole;
import com.aaravlabs.synapse.bench.shared.Hist;
import com.aaravlabs.synapse.bench.shared.LatencyProbe;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.Scenario;
import com.aaravlabs.synapse.bench.shared.SimMotor;
import com.aaravlabs.synapse.bench.shared.SimPlant;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;
import com.aaravlabs.synapse.ftc.HardwareActions;
import com.seattlesolvers.solverslib.command.CommandScheduler;
import com.seattlesolvers.solverslib.command.SubsystemBase;
import com.seattlesolvers.solverslib.gamepad.GamepadEx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micro layer: Synapse optimization targets plus SolversLib comparators and the
 * {@code micro.sim.deviceWrite} mock-budget probe. Custom harness (not JMH —
 * the interesting paths are cross-thread dispatch). Warmup rounds, percentile
 * histograms, volatile blackhole sink.
 */
public final class MicroBench {

    public static final class Result {
        public final String name;
        public final double nsPerOp;
        public final long p50;
        public final long p99;
        public final long count;

        Result(String name, double nsPerOp, long p50, long p99, long count) {
            this.name = name;
            this.nsPerOp = nsPerOp;
            this.p50 = p50;
            this.p99 = p99;
            this.count = count;
        }
    }

    /**
     * Framework-level dispatch measurements used by the mock budget gate: paths
     * that cross a framework dispatch boundary (callback pool, hardware thread,
     * reflective handler invoke) and therefore contain the device write on the
     * measured path. Local primitive micros ({@code recordLatest},
     * {@code schedulerRun}, {@code buttonRead}) are optimization targets, not
     * budget references — they contain no dispatch hop for the mock to hide in.
     */
    public static final List<String> DISPATCH_BENCHES = List.of(
            "micro.publish.subscribers1",
            "micro.publish.subscribers8",
            "micro.publish.annotationSubscriber",
            "micro.hardware.run",
            "micro.hardware.call");

    private final int warmupOps;
    private final int measureOps;
    private final int sampleCount;

    public MicroBench(boolean full) {
        this.warmupOps = full ? 200_000 : 20_000;
        this.measureOps = full ? 1_000_000 : 100_000;
        this.sampleCount = full ? 20_000 : 4_000;
    }

    public Map<String, Result> runAll() {
        Map<String, Result> out = new LinkedHashMap<>();
        out.put("micro.raw.directCall", batch("micro.raw.directCall", this::rawDirectCall));
        out.putAll(publishBenches());
        out.putAll(topicBenches());
        out.put("micro.subscribe.churn", batch("micro.subscribe.churn", this::subscribeChurn));
        out.putAll(hardwareBenches());
        out.put("micro.gamepad.adaptorPoll", batch("micro.gamepad.adaptorPoll", this::gamepadAdaptorPoll));
        out.putAll(solverslibBenches());
        out.put("micro.sim.deviceWrite", batch("micro.sim.deviceWrite", this::simDeviceWrite));
        return out;
    }

    // ------------------------------------------------------------------ raw

    private static final long RAW_SINK_HOLDER = 0;

    private long rawDirectCall() {
        long v = RAW_SINK_HOLDER + 1;
        Blackhole.consume(v);
        return v;
    }

    // -------------------------------------------------------------- publish

    private Map<String, Result> publishBenches() {
        Map<String, Result> out = new LinkedHashMap<>();
        OrchestratorImpl orch = (OrchestratorImpl) Orchestrator.create("micro-pub", LogSink.SILENT);
        try {
            out.put("micro.publish.subscribers0",
                    batch("micro.publish.subscribers0", () -> publishOp(orch, "pub0", 0)));

            out.put("micro.publish.subscribers1",
                    endToEnd("micro.publish.subscribers1", samples -> publishEndToEnd(orch, "pub1", 1, samples)));
            out.put("micro.publish.subscribers8",
                    endToEnd("micro.publish.subscribers8", samples -> publishEndToEnd(orch, "pub8", 8, samples)));

            AtomicLong annotatedDone = new AtomicLong();
            Node node = new Node(orch) {
                @SubscribedTo(topic = "pubAnn")
                public void onMsg(Integer v) {
                    annotatedDone.set(System.nanoTime());
                }
            };
            orch.registerNode("micro-ann", node);
            out.put("micro.publish.annotationSubscriber",
                    endToEnd("micro.publish.annotationSubscriber", samples -> {
                        for (int i = 0; i < samples.length; i++) {
                            annotatedDone.set(0);
                            long t0 = System.nanoTime();
                            orch.publish("pubAnn", i);
                            long t1 = awaitStamp(annotatedDone);
                            samples[i] = t1 - t0;
                        }
                        return samples.length;
                    }));
        } finally {
            orch.close();
        }
        return out;
    }

    private long publishOp(Orchestrator orch, String topic, int subscribers) {
        orch.publish(topic, 42);
        return 42;
    }

    private int publishEndToEnd(Orchestrator orch, String topic, int subscribers, long[] samples) {
        AtomicLong done = new AtomicLong();
        AtomicInteger remaining = new AtomicInteger();
        List<Subscription> subs = new ArrayList<>();
        for (int s = 0; s < subscribers; s++) {
            subs.add(orch.subscribe(topic, Integer.class, v -> {
                if (remaining.decrementAndGet() == 0) done.set(System.nanoTime());
            }));
        }
        for (int i = 0; i < samples.length; i++) {
            remaining.set(subscribers);
            done.set(0);
            long t0 = System.nanoTime();
            orch.publish(topic, i);
            long t1 = awaitStamp(done);
            samples[i] = t1 - t0;
        }
        for (Subscription s : subs) s.unsubscribe();
        return samples.length;
    }

    private static long awaitStamp(AtomicLong stamp) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true) {
            long v = stamp.get();
            if (v != 0) return v;
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("dispatch never completed");
            }
            Thread.onSpinWait();
        }
    }

    // ---------------------------------------------------------------- topic

    private Map<String, Result> topicBenches() {
        Map<String, Result> out = new LinkedHashMap<>();
        Orchestrator orch = Orchestrator.create("micro-topic", LogSink.SILENT);
        try {
            // micro.topic.recordLatest measures publish() to a zero-subscriber
            // topic: the synchronous path is exactly Topic.recordLatest.
            orch.getOrCreateTopic("topicMicro0", Integer.class);
            Topic<Integer> topic = orch.getOrCreateTopic("topicMicro", Integer.class);
            for (int i = 0; i < 1000; i++) {
                orch.publish("topicMicro0", i);
                Blackhole.consume(topic.latestValue().orElse(0));
            }
            out.put("micro.topic.recordLatest",
                    batch("micro.topic.recordLatest", () -> publishOp(orch, "topicMicro0", 0)));
            out.put("micro.topic.latestValue",
                    batch("micro.topic.latestValue", () -> {
                        Integer v = topic.latestValue().orElse(0);
                        Blackhole.consume(v);
                        return v;
                    }));

            out.put("micro.topic.recordLatest.1p1c", contention(orch, 1, 1));
            out.put("micro.topic.recordLatest.4p4c", contention(orch, 4, 4));
        } finally {
            orch.close();
        }
        return out;
    }

    /**
     * Publisher-side cost of {@code Topic.recordLatest} (via 0-subscriber publish)
     * while consumers hammer {@code latestValue()}. 1P1C and 4P4C contention.
     */
    private Result contention(Orchestrator orch, int publishers, int consumers) {
        String name = "micro.topic.recordLatest." + publishers + "p" + consumers + "c";
        AtomicLong published = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        AtomicBoolean run = new AtomicBoolean(true);
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            Thread t = new Thread(() -> {
                while (run.get()) {
                    orch.getLatestValue("contend", Integer.class);
                    consumed.incrementAndGet();
                }
            }, "micro-consumer");
            t.setDaemon(true);
            threads.add(t);
        }
        for (int p = 0; p < publishers; p++) {
            Thread t = new Thread(() -> {
                while (run.get()) {
                    orch.publish("contend", 1);
                    published.incrementAndGet();
                }
            }, "micro-publisher");
            t.setDaemon(true);
            threads.add(t);
        }
        for (Thread t : threads) t.start();
        try {
            Thread.sleep(200);
            Hist hist = new Hist();
            long totalOps = 0;
            long totalNanos = 0;
            long budgetMs = (long) Math.max(200, measureOps / 5000.0);
            int intervals = (int) Math.max(8, Math.min(40, budgetMs / 50));
            for (int i = 0; i < intervals; i++) {
                long ops0 = published.get();
                long t0 = System.nanoTime();
                Thread.sleep(50);
                long ops1 = published.get();
                long t1 = System.nanoTime();
                long ops = ops1 - ops0;
                if (ops > 0) {
                    long ns = t1 - t0;
                    hist.record(ns / ops);
                    totalOps += ops;
                    totalNanos += ns;
                }
            }
            run.set(false);
            for (Thread t : threads) t.join(500);
            double nsPerOp = totalOps > 0 ? (double) totalNanos / totalOps : 0.0;
            Hist.Snapshot s = hist.snapshot();
            Blackhole.consume(consumed.get());
            return new Result(name, nsPerOp, s.p50, s.p99, totalOps);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.set(false);
            return new Result(name, 0, 0, 0, 0);
        }
    }

    private long subscribeChurn() {
        Orchestrator orch = churnOrch;
        Subscription s = orch.subscribe("churn", Integer.class, v -> Blackhole.consume(v));
        s.unsubscribe();
        return 1;
    }

    private Orchestrator churnOrch;

    // ------------------------------------------------------------- hardware

    private Map<String, Result> hardwareBenches() {
        Map<String, Result> out = new LinkedHashMap<>();
        Orchestrator orch = Orchestrator.create("micro-hw", LogSink.SILENT);
        try {
            HardwareActions hw = orch.hardware();
            out.put("micro.hardware.run",
                    endToEnd("micro.hardware.run", samples -> {
                        AtomicLong done = new AtomicLong();
                        for (int i = 0; i < samples.length; i++) {
                            done.set(0);
                            long t0 = System.nanoTime();
                            hw.run(() -> done.set(System.nanoTime()));
                            samples[i] = awaitStamp(done) - t0;
                        }
                        return samples.length;
                    }));
            out.put("micro.hardware.call",
                    endToEnd("micro.hardware.call", samples -> {
                        for (int i = 0; i < samples.length; i++) {
                            long t0 = System.nanoTime();
                            try {
                                long v = hw.call(() -> System.nanoTime());
                                samples[i] = v - t0;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        return samples.length;
                    }));
        } finally {
            orch.close();
        }
        return out;
    }

    private long gamepadAdaptorPoll() {
        GamepadAdaptorHolder holder = adaptorHolder;
        holder.adaptor.poll();
        return 1;
    }

    private static final class GamepadAdaptorHolder {
        final GamepadAdaptor adaptor;
        final Orchestrator orch;

        GamepadAdaptorHolder() {
            this.orch = Orchestrator.create("micro-gp", LogSink.SILENT);
            World w = new World(Scenario.S0_MinimalDrive, 1, new Metrics());
            GamepadAdaptor.attach(orch, w.gamepad1(), "g1");
            this.adaptor = (GamepadAdaptor) orch.findNode("GamepadAdaptor:g1")
                    .orElseThrow(() -> new IllegalStateException("adaptor not registered"));
        }
    }

    private GamepadAdaptorHolder adaptorHolder;

    // ----------------------------------------------------------- solverslib

    private Map<String, Result> solverslibBenches() {
        Map<String, Result> out = new LinkedHashMap<>();
        CommandScheduler scheduler = CommandScheduler.getInstance();
        scheduler.reset();
        scheduler.clearButtons();
        try {
            List<SubsystemBase> one = new ArrayList<>();
            one.add(new SubsystemBase() {
            });
            scheduler.registerSubsystem(one.get(0));
            out.put("micro.solverslib.schedulerRun1",
                    batch("micro.solverslib.schedulerRun1", () -> {
                        scheduler.run();
                        return 1;
                    }));

            scheduler.reset();
            List<SubsystemBase> eight = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                SubsystemBase s = new SubsystemBase() {
                };
                eight.add(s);
            }
            scheduler.registerSubsystem(eight.toArray(new SubsystemBase[0]));
            out.put("micro.solverslib.schedulerRun8",
                    batch("micro.solverslib.schedulerRun8", () -> {
                        scheduler.run();
                        return 1;
                    }));
            scheduler.reset();

            GamepadEx gamepadEx = new GamepadEx(new com.qualcomm.robotcore.hardware.Gamepad());
            out.put("micro.solverslib.buttonRead",
                    batch("micro.solverslib.buttonRead", () -> {
                        gamepadEx.readButtons();
                        return 1;
                    }));
        } finally {
            scheduler.reset();
            scheduler.clearButtons();
        }
        return out;
    }

    // ----------------------------------------------------------------- mock

    private long simDeviceWrite() {
        SimDeviceHolder holder = simDevice;
        holder.probe.stimulus(System.nanoTime(), 1.0);
        holder.motor.setPower(1.0);
        return 1;
    }

    private static final class SimDeviceHolder {
        final SimPlant plant;
        final LatencyProbe probe;
        final SimMotor motor;

        SimDeviceHolder() {
            this.plant = new SimPlant(new com.aaravlabs.synapse.bench.shared.Setpoints(0), false, false);
            this.probe = new LatencyProbe("micro");
            this.probe.setRecording(true);
            this.motor = new SimMotor(plant, SimPlant.LEFT, probe);
        }
    }

    private SimDeviceHolder simDevice;

    // ------------------------------------------------------------- plumbing

    /** Fixed-work op returning a blackhole sink; timed in batches. */
    private interface BatchOp {
        long run();
    }

    /** Fills per-op latency samples; returns the number of samples taken. */
    private interface SampleFiller {
        int fill(long[] samples);
    }

    private Result batch(String name, BatchOp op) {
        // lazy init of per-bench fixtures
        switch (name) {
            case "micro.subscribe.churn":
                churnOrch = Orchestrator.create("micro-churn", LogSink.SILENT);
                break;
            case "micro.gamepad.adaptorPoll":
                adaptorHolder = new GamepadAdaptorHolder();
                break;
            case "micro.sim.deviceWrite":
                simDevice = new SimDeviceHolder();
                break;
            default:
                break;
        }
        try {
            int itersPerBatch = Math.max(16, measureOps / 200);
            int batches = 200;
            for (int i = 0; i < warmupOps / itersPerBatch; i++) {
                long acc = 0;
                for (int j = 0; j < itersPerBatch; j++) acc += op.run();
                Blackhole.consume(acc);
            }
            long[] samples = new long[batches];
            for (int b = 0; b < batches; b++) {
                long t0 = System.nanoTime();
                long acc = 0;
                for (int j = 0; j < itersPerBatch; j++) acc += op.run();
                long dt = System.nanoTime() - t0;
                Blackhole.consume(acc);
                samples[b] = dt / itersPerBatch;
            }
            Hist h = new Hist();
            for (long s : samples) h.record(s);
            Hist.Snapshot snap = h.snapshot();
            return new Result(name, snap.mean, snap.p50, snap.p99, (long) batches * itersPerBatch);
        } finally {
            cleanup(name);
        }
    }

    private Result endToEnd(String name, SampleFiller filler) {
        long[] warm = new long[Math.min(500, sampleCount)];
        filler.fill(warm);
        long[] samples = new long[sampleCount];
        int n = filler.fill(samples);
        Hist h = new Hist();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            h.record(samples[i]);
            sum += samples[i];
        }
        Hist.Snapshot snap = h.snapshot();
        double nsPerOp = n > 0 ? sum / (double) n : 0.0;
        return new Result(name, nsPerOp, snap.p50, snap.p99, n);
    }

    private void cleanup(String name) {
        switch (name) {
            case "micro.subscribe.churn":
                if (churnOrch != null) {
                    churnOrch.close();
                    churnOrch = null;
                }
                break;
            case "micro.gamepad.adaptorPoll":
                if (adaptorHolder != null) {
                    adaptorHolder.orch.close();
                    adaptorHolder = null;
                }
                break;
            default:
                break;
        }
    }

    /**
     * Gate 2: {@code micro.sim.deviceWrite} must cost less than 5% of the
     * smallest framework-level dispatch measurement, so mock noise cannot
     * dominate or mask framework overhead.
     */
    public static double mockBudgetRatioPct(Map<String, Result> micros) {
        Result deviceWrite = micros.get("micro.sim.deviceWrite");
        if (deviceWrite == null) return Double.NaN;
        double minDispatch = Double.MAX_VALUE;
        for (String name : DISPATCH_BENCHES) {
            Result r = micros.get(name);
            if (r != null && r.nsPerOp > 0 && r.nsPerOp < minDispatch) {
                minDispatch = r.nsPerOp;
            }
        }
        if (minDispatch == Double.MAX_VALUE) return Double.NaN;
        return 100.0 * deviceWrite.nsPerOp / minDispatch;
    }
}
