package com.aaravlabs.synapse.bench.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named measurement sinks for one scenario × style run. Style code holds direct
 * {@link TaskMeter}/{@link LatencyProbe} references and stamps inside the bodies
 * the framework invokes — never in harness glue around a framework call.
 */
public final class Metrics {

    public static final class TaskSnapshot {
        public final String name;
        public final double targetHz;
        public final double achievedHz;
        public final long jitterP99Ns;
        public final long count;

        TaskSnapshot(String name, double targetHz, double achievedHz, long jitterP99Ns, long count) {
            this.name = name;
            this.targetHz = targetHz;
            this.achievedHz = achievedHz;
            this.jitterP99Ns = jitterP99Ns;
            this.count = count;
        }
    }

    private final Map<String, TaskMeter> tasks = new ConcurrentHashMap<>();
    private final Map<String, LatencyProbe> probes = new ConcurrentHashMap<>();
    private final List<TaskMeter> orderedTasks = new ArrayList<>();
    private final List<LatencyProbe> orderedProbes = new ArrayList<>();

    private final java.util.concurrent.atomic.LongAdder loopIterations =
            new java.util.concurrent.atomic.LongAdder();
    private volatile long windowStartNanos;
    private volatile long windowEndNanos;
    private volatile boolean allocEnabled;
    private long allocStartBytes = -1;
    private long allocEndBytes = -1;

    public TaskMeter task(String name, double targetHz) {
        TaskMeter m = tasks.get(name);
        if (m == null) {
            m = new TaskMeter(name, targetHz);
            TaskMeter prev = tasks.putIfAbsent(name, m);
            if (prev != null) return prev;
            synchronized (orderedTasks) {
                orderedTasks.add(m);
            }
        }
        return m;
    }

    public LatencyProbe probe(String name) {
        LatencyProbe p = probes.get(name);
        if (p == null) {
            p = new LatencyProbe(name);
            LatencyProbe prev = probes.putIfAbsent(name, p);
            if (prev != null) return prev;
            synchronized (orderedProbes) {
                orderedProbes.add(p);
            }
        }
        return p;
    }

    public void setAllocEnabled(boolean on) {
        allocEnabled = on;
    }

    public void startWindow() {
        windowStartNanos = System.nanoTime();
        for (TaskMeter m : snapshotTasks()) {
            m.reset();
            m.setRecording(true);
        }
        for (LatencyProbe p : snapshotProbes()) {
            p.reset();
            p.setRecording(true);
        }
        loopIterations.reset();
        if (allocEnabled) allocStartBytes = threadAllocated();
    }

    public void endWindow() {
        windowEndNanos = System.nanoTime();
        for (TaskMeter m : snapshotTasks()) m.setRecording(false);
        for (LatencyProbe p : snapshotProbes()) p.setRecording(false);
        if (allocEnabled) allocEndBytes = threadAllocated();
    }

    public void countLoopIteration() {
        loopIterations.increment();
    }

    public long windowNanos() {
        return windowEndNanos - windowStartNanos;
    }

    public double loopHz() {
        double sec = windowNanos() / 1e9;
        return sec > 0 ? loopIterations.sum() / sec : 0.0;
    }

    public double allocBytesPerSec() {
        if (!allocEnabled || allocStartBytes < 0 || allocEndBytes < 0) return 0.0;
        double sec = windowNanos() / 1e9;
        return sec > 0 ? (allocEndBytes - allocStartBytes) / sec : 0.0;
    }

    public List<TaskSnapshot> taskSnapshots() {
        List<TaskSnapshot> out = new ArrayList<>();
        for (TaskMeter m : snapshotTasks()) {
            Hist.Snapshot s = m.periodSnapshot();
            out.add(new TaskSnapshot(m.name(), m.targetHz(), m.achievedHz(), s.p99, m.count()));
        }
        return out;
    }

    public Map<String, Hist.Snapshot> probeSnapshots() {
        Map<String, Hist.Snapshot> out = new LinkedHashMap<>();
        for (LatencyProbe p : snapshotProbes()) {
            out.put(p.name(), p.hist().snapshot());
        }
        return out;
    }

    public Hist.Snapshot probeSnapshot(String name) {
        LatencyProbe p = probes.get(name);
        return p == null ? Hist.Snapshot.empty() : p.hist().snapshot();
    }

    private List<TaskMeter> snapshotTasks() {
        synchronized (orderedTasks) {
            return new ArrayList<>(orderedTasks);
        }
    }

    private List<LatencyProbe> snapshotProbes() {
        synchronized (orderedProbes) {
            return new ArrayList<>(orderedProbes);
        }
    }

    private long threadAllocated() {
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean) {
                com.sun.management.ThreadMXBean b = (com.sun.management.ThreadMXBean) bean;
                long total = 0;
                for (long v : b.getThreadAllocatedBytes(b.getAllThreadIds())) {
                    if (v > 0) total += v;
                }
                return total;
            }
        } catch (Throwable ignored) {
            // optional metric
        }
        return -1;
    }
}
