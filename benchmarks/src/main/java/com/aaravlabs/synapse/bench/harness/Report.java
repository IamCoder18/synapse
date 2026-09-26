package com.aaravlabs.synapse.bench.harness;

import com.aaravlabs.synapse.bench.shared.Hist;
import com.aaravlabs.synapse.bench.shared.Metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON + Markdown result writers. Schema is versioned and stable so agents can
 * diff and compare runs mechanically.
 */
public final class Report {

    /** One scenario × style measurement (possibly a median of rounds/forks). */
    public static final class Pair {
        public final String scenario;
        public final String style;
        public final Map<String, Object> latencyActuationNs;
        public final Map<String, Object> taskRates;
        public final Map<String, Object> trackingError;
        public final double loopHz;
        public final double allocBytesPerSec;
        public final long gcCount;
        public final long gcMillis;

        public Pair(String scenario, String style,
                    Map<String, Object> latencyActuationNs,
                    Map<String, Object> taskRates,
                    Map<String, Object> trackingError,
                    double loopHz,
                    double allocBytesPerSec,
                    long gcCount,
                    long gcMillis) {
            this.scenario = scenario;
            this.style = style;
            this.latencyActuationNs = latencyActuationNs;
            this.taskRates = taskRates;
            this.trackingError = trackingError;
            this.loopHz = loopHz;
            this.allocBytesPerSec = allocBytesPerSec;
            this.gcCount = gcCount;
            this.gcMillis = gcMillis;
        }

        public static Pair fromSnapshot(String scenario, String style,
                                        Hist.Snapshot latency,
                                        List<Metrics.TaskSnapshot> tasks,
                                        double liftRmse,
                                        double headingRmse,
                                        double loopHz,
                                        double allocBytesPerSec,
                                        long gcCount,
                                        long gcMillis) {
            Map<String, Object> lat = new LinkedHashMap<>();
            lat.put("p50", latency.p50);
            lat.put("p90", latency.p90);
            lat.put("p99", latency.p99);
            lat.put("p999", latency.p999);
            lat.put("max", latency.max);
            lat.put("min", latency.min);
            lat.put("mean", latency.mean);
            lat.put("count", latency.count);

            Map<String, Object> rates = new LinkedHashMap<>();
            for (Metrics.TaskSnapshot t : tasks) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("targetHz", t.targetHz);
                entry.put("achievedHz", t.achievedHz);
                entry.put("jitterP99Ns", t.jitterP99Ns);
                entry.put("jitterP999Ns", t.jitterP999Ns);
                entry.put("deadlineMissPct", t.deadlineMissPct);
                entry.put("count", t.count);
                rates.put(t.name, entry);
            }

            Map<String, Object> tracking = new LinkedHashMap<>();
            tracking.put("liftRmse", liftRmse);
            tracking.put("headingRmse", headingRmse);

            return new Pair(scenario, style, lat, rates, tracking, loopHz, allocBytesPerSec,
                    gcCount, gcMillis);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", scenario);
            m.put("style", style);
            m.put("latencyActuationNs", latencyActuationNs);
            m.put("taskRates", taskRates);
            m.put("trackingError", trackingError);
            m.put("loopHz", loopHz);
            m.put("allocBytesPerSec", allocBytesPerSec);
            m.put("gcCount", gcCount);
            m.put("gcMillis", gcMillis);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Pair fromMap(Map<String, Object> m) {
            return new Pair(
                    (String) m.get("scenario"),
                    (String) m.get("style"),
                    (Map<String, Object>) m.get("latencyActuationNs"),
                    (Map<String, Object>) m.get("taskRates"),
                    (Map<String, Object>) m.get("trackingError"),
                    ((Number) m.getOrDefault("loopHz", 0)).doubleValue(),
                    ((Number) m.getOrDefault("allocBytesPerSec", 0)).doubleValue(),
                    ((Number) m.getOrDefault("gcCount", 0)).longValue(),
                    ((Number) m.getOrDefault("gcMillis", 0)).longValue());
        }
    }

    private Report() {
    }

    public static Map<String, Object> document(Map<String, String> env,
                                               List<Pair> pairs,
                                               Map<String, ?> micro,
                                               Map<String, Object> gates) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", 1);
        doc.put("env", env);
        List<Object> scenarios = new ArrayList<>();
        for (Pair p : pairs) scenarios.add(p.toMap());
        doc.put("scenarios", scenarios);
        doc.put("micro", micro);
        doc.put("gates", gates);
        return doc;
    }

    public static void writeJson(Path path, Map<String, Object> doc) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, Json.write(doc).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeMarkdown(Path path, Map<String, Object> doc) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, markdown(doc).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static String markdown(Map<String, Object> doc) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> env = (Map<String, String>) doc.get("env");
        sb.append("# Synapse benchmark results\n\n");
        sb.append("Mode: ").append(env.get("mode"))
                .append(" · seed ").append(env.get("seed"))
                .append(" · forks ").append(env.get("forks"))
                .append(" · rounds ").append(env.get("rounds")).append("\n\n");
        sb.append("- OS: ").append(env.get("os")).append('\n');
        sb.append("- JDK: ").append(env.get("jdk")).append('\n');
        sb.append("- CPU: ").append(env.get("cpu")).append('\n');
        sb.append("- git: ").append(env.get("gitSha")).append('\n');
        sb.append("- timestamp: ").append(env.get("timestamp")).append('\n');
        sb.append("\n## Scenario ladder\n\n");
        sb.append("| scenario | style | actuation p50 (ns) | actuation p99 (ns) | actuation p99.9 (ns) |");
        sb.append(" max task miss % | lift RMSE | heading RMSE | loopHz | GC ms |");
        Map<String, Object> firstRates = new LinkedHashMap<>();
        List<Object> scenarios = (List<Object>) doc.get("scenarios");
        for (Object o : scenarios) {
            Map<String, Object> p = (Map<String, Object>) o;
            Map<String, Object> rates = (Map<String, Object>) p.get("taskRates");
            for (Object k : rates.keySet()) {
                String key = String.valueOf(k);
                if (!firstRates.containsKey(key)) firstRates.put(key, rates.get(k));
            }
        }
        for (Object k : firstRates.keySet()) {
            sb.append(' ').append(k).append(" Hz |");
        }
        sb.append('\n');
        sb.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (int i = 0; i < firstRates.size(); i++) sb.append(" ---: |");
        sb.append('\n');
        for (Object o : scenarios) {
            Map<String, Object> p = (Map<String, Object>) o;
            Map<String, Object> lat = (Map<String, Object>) p.get("latencyActuationNs");
            Map<String, Object> tracking = (Map<String, Object>) p.get("trackingError");
            Map<String, Object> rates = (Map<String, Object>) p.get("taskRates");
            double maxMiss = 0;
            for (Object k : firstRates.keySet()) {
                Map<String, Object> rate = (Map<String, Object>) rates.get(k);
                if (rate == null) continue;
                Object miss = rate.get("deadlineMissPct");
                if (miss instanceof Number) {
                    maxMiss = Math.max(maxMiss, ((Number) miss).doubleValue());
                }
            }
            sb.append("| ").append(p.get("scenario"))
                    .append(" | ").append(p.get("style"))
                    .append(" | ").append(fmt(lat.get("p50")))
                    .append(" | ").append(fmt(lat.get("p99")))
                    .append(" | ").append(fmt(lat.get("p999")))
                    .append(" | ").append(fmt(maxMiss))
                    .append(" | ").append(fmt(tracking.get("liftRmse")))
                    .append(" | ").append(fmt(tracking.get("headingRmse")))
                    .append(" | ").append(fmt(p.get("loopHz")))
                    .append(" | ").append(fmt(p.get("gcMillis")));
            for (Object k : firstRates.keySet()) {
                Map<String, Object> rate = (Map<String, Object>) rates.get(k);
                sb.append(" | ").append(rate == null ? "—" : fmt(rate.get("achievedHz")));
            }
            sb.append(" |\n");
        }

        sb.append("\n## Micro layer (ns/op)\n\n");
        sb.append("| bench | ns/op | p50 | p99 | count |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: |\n");
        Map<String, Object> micro = (Map<String, Object>) doc.get("micro");
        for (Map.Entry<String, Object> e : micro.entrySet()) {
            Map<String, Object> r = (Map<String, Object>) e.getValue();
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(fmt(r.get("nsPerOp")))
                    .append(" | ").append(fmt(r.get("p50")))
                    .append(" | ").append(fmt(r.get("p99")))
                    .append(" | ").append(fmt(r.get("count")))
                    .append(" |\n");
        }

        Map<String, Object> gates = (Map<String, Object>) doc.get("gates");
        sb.append("\n## Gates\n\n");
        for (Map.Entry<String, Object> e : gates.entrySet()) {
            if ("messages".equals(e.getKey())) {
                sb.append("\n```\n");
                for (Object line : (List<Object>) e.getValue()) {
                    sb.append(line).append('\n');
                }
                sb.append("```\n");
            } else {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        sb.append("\n## Known confounds\n\n");
        sb.append("- Desktop JVM, simulated plant: absolute numbers are not robot-bus latencies.\n");
        sb.append("- Vision and logger kernels are duration-targeted busy work (~3 ms / ~15 ms),\n");
        sb.append("  so the load profile is machine-independent while absolute times are real.\n");
        sb.append("- Axis sign conventions differ by framework API (raw fields vs GamepadEx);\n");
        sb.append("  workload shape (reads + arithmetic + writes) is identical across styles.\n");
        sb.append("- Lynx bus, DS packet link, sensor quantization/noise/dropouts, and small-heap\n");
        sb.append("  GC pressure are modeled Control-Hub-class structure, not mock overhead.\n");
        sb.append("- See `benchmarks/README.md` for methodology and fairness rules.\n");
        return sb.toString();
    }

    private static String fmt(Object v) {
        if (v == null) return "—";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d != 0 && Math.abs(d) < 1) return String.format(Locale.ROOT, "%.5f", d);
            return String.format(Locale.ROOT, "%.2f", d);
        }
        return v.toString();
    }
}
