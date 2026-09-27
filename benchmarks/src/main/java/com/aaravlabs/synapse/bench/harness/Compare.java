package com.aaravlabs.synapse.bench.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Regression comparison of two result documents with tolerances and
 * agent-friendly exit codes: 0 = within tolerance, 1 = regression, 2 = missing
 * metrics.
 */
public final class Compare {

    public static final int EXIT_OK = 0;
    public static final int EXIT_REGRESSION = 1;
    public static final int EXIT_MISSING = 2;

    private static final class MetricRef {
        final String label;
        final double baseline;
        final double latest;
        final boolean lowerIsBetter;
        final double absFloor;

        MetricRef(String label, double baseline, double latest, boolean lowerIsBetter) {
            this.label = label;
            this.baseline = baseline;
            this.latest = latest;
            this.lowerIsBetter = lowerIsBetter;
            // Absolute noise floor only for nanosecond-scale metrics; unitless
            // metrics (RMSE, Hz) rely on the relative tolerance alone so small
            // magnitudes cannot hide behind a fixed 1.0-unit floor.
            this.absFloor = (label.endsWith("Ns") || label.endsWith("nsPerOp")
                    || label.contains(".latency.")) ? 1.0 : 0.0;
        }

        boolean regressed(double tolerance) {
            if (lowerIsBetter) {
                return latest > baseline * (1.0 + tolerance) && latest - baseline > absFloor;
            }
            return latest < baseline * (1.0 - tolerance) && baseline - latest > absFloor;
        }

        double ratio() {
            if (baseline == 0) return latest == 0 ? 1.0 : Double.POSITIVE_INFINITY;
            return latest / baseline;
        }
    }

    private Compare() {
    }

    public static int run(Path baselinePath, Path latestPath, double tolerance, boolean quiet) throws IOException {
        Map<String, Object> baseline = Json.parseObject(
                new String(Files.readAllBytes(baselinePath), StandardCharsets.UTF_8));
        Map<String, Object> latest = Json.parseObject(
                new String(Files.readAllBytes(latestPath), StandardCharsets.UTF_8));
        return compare(baseline, latest, tolerance, quiet);
    }

    @SuppressWarnings("unchecked")
    public static int compare(Map<String, Object> baseline, Map<String, Object> latest,
                              double tolerance, boolean quiet) {
        List<MetricRef> metrics = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Map<String, Map<String, Object>> baseScenarios = indexScenarios(baseline);
        Map<String, Map<String, Object>> latestScenarios = indexScenarios(latest);

        for (Map.Entry<String, Map<String, Object>> e : baseScenarios.entrySet()) {
            String key = e.getKey();
            Map<String, Object> b = e.getValue();
            Map<String, Object> l = latestScenarios.get(key);
            if (l == null) {
                missing.add("scenario " + key);
                continue;
            }
            collectScenario(key, b, l, metrics, missing);
        }
        for (String key : latestScenarios.keySet()) {
            if (!baseScenarios.containsKey(key)) missing.add("scenario " + key + " (only in latest)");
        }

        Map<String, Object> baseMicro = (Map<String, Object>) baseline.getOrDefault("micro", Map.of());
        Map<String, Object> latestMicro = (Map<String, Object>) latest.getOrDefault("micro", Map.of());
        for (Map.Entry<String, Object> e : baseMicro.entrySet()) {
            String name = e.getKey();
            Map<String, Object> b = (Map<String, Object>) e.getValue();
            Map<String, Object> l = (Map<String, Object>) latestMicro.get(name);
            if (l == null) {
                missing.add("micro " + name);
                continue;
            }
            double bv = num(b.get("nsPerOp"));
            double lv = num(l.get("nsPerOp"));
            if (bv <= 0) {
                missing.add("micro " + name + " (baseline nsPerOp not measurable)");
                continue;
            }
            metrics.add(new MetricRef("micro." + name + ".nsPerOp", bv, lv, true));
        }

        boolean regressions = false;
        List<String> rows = new ArrayList<>();
        rows.add(String.format(Locale.ROOT, "%-52s %12s %12s %8s %s",
                "metric", "baseline", "latest", "ratio", "status"));
        for (MetricRef m : metrics) {
            boolean bad = m.regressed(tolerance);
            if (bad) regressions = true;
            rows.add(String.format(Locale.ROOT, "%-52s %12.2f %12.2f %8.3f %s",
                    m.label, m.baseline, m.latest, m.ratio(), bad ? "REGRESSION" : "ok"));
        }
        if (!quiet) {
            for (String row : rows) System.out.println(row);
            if (!missing.isEmpty()) {
                System.out.println("missing metrics:");
                for (String s : missing) System.out.println("  " + s);
            }
        }

        if (regressions) return EXIT_REGRESSION;
        if (!missing.isEmpty()) return EXIT_MISSING;
        return EXIT_OK;
    }

    @SuppressWarnings("unchecked")
    private static void collectScenario(String key, Map<String, Object> b, Map<String, Object> l,
                                        List<MetricRef> metrics, List<String> missing) {
        Map<String, Object> blat = (Map<String, Object>) b.get("latencyActuationNs");
        Map<String, Object> llat = (Map<String, Object>) l.get("latencyActuationNs");
        for (String p : new String[] {"p50", "p90", "p99", "p999", "max"}) {
            double bv = num(blat == null ? null : blat.get(p));
            if (bv <= 0) {
                if (blat != null && blat.containsKey(p)) missing.add(key + ".latencyActuationNs." + p);
                continue;
            }
            if (!(llat != null && llat.get(p) instanceof Number)) {
                missing.add(key + ".latencyActuationNs." + p);
                continue;
            }
            metrics.add(new MetricRef(key + ".latency." + p, bv, num(llat.get(p)), true));
        }

        Map<String, Object> brates = (Map<String, Object>) b.get("taskRates");
        Map<String, Object> lrates = (Map<String, Object>) l.get("taskRates");
        for (Map.Entry<String, Object> e : brates.entrySet()) {
            String task = e.getKey();
            Map<String, Object> br = (Map<String, Object>) e.getValue();
            Map<String, Object> lr = (Map<String, Object>) lrates.get(task);
            if (lr == null) {
                missing.add(key + ".taskRates." + task);
                continue;
            }
            double bHz = num(br.get("achievedHz"));
            if (bHz > 0) {
                if (!(lr.get("achievedHz") instanceof Number)) {
                    missing.add(key + ".taskRates." + task + ".achievedHz");
                } else {
                    metrics.add(new MetricRef(key + "." + task + ".achievedHz", bHz, num(lr.get("achievedHz")), false));
                }
            }
            double bJ = num(br.get("jitterP99Ns"));
            if (bJ > 0) {
                if (!(lr.get("jitterP99Ns") instanceof Number)) {
                    missing.add(key + ".taskRates." + task + ".jitterP99Ns");
                } else {
                    metrics.add(new MetricRef(key + "." + task + ".jitterP99Ns", bJ, num(lr.get("jitterP99Ns")), true));
                }
            }
        }

        Map<String, Object> btr = (Map<String, Object>) b.getOrDefault("trackingError", Map.of());
        Map<String, Object> ltr = (Map<String, Object>) l.getOrDefault("trackingError", Map.of());
        for (String p : new String[] {"liftRmse", "headingRmse"}) {
            double bv = num(btr.get(p));
            if (bv > 0) {
                if (!(ltr.get(p) instanceof Number)) {
                    missing.add(key + "." + p);
                    continue;
                }
                metrics.add(new MetricRef(key + "." + p, bv, num(ltr.get(p)), true));
            }
        }

        double bLoop = num(b.get("loopHz"));
        double lLoop = num(l.get("loopHz"));
        if (bLoop > 0) {
            metrics.add(new MetricRef(key + ".loopHz", bLoop, lLoop, false));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> indexScenarios(Map<String, Object> doc) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object list = doc.get("scenarios");
        if (!(list instanceof List)) return out;
        for (Object o : (List<Object>) list) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put(m.get("scenario") + "/" + m.get("style"), m);
        }
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    /**
     * Self-test of the regression detector (acceptance criterion): a synthetic
     * 2× slowdown of the {@code OrchestratorImpl.publish} path (represented by
     * {@code micro.publish.subscribers1}) must exit 1, and comparing a document
     * to itself must exit 0.
     */
    @SuppressWarnings("unchecked")
    public static int selfTest(Path anyResult) throws IOException {
        Map<String, Object> base = Json.parseObject(
                new String(Files.readAllBytes(anyResult), StandardCharsets.UTF_8));
        Map<String, Object> mutated = Json.parseObject(Json.write(base));
        Map<String, Object> micro = (Map<String, Object>) mutated.get("micro");
        Map<String, Object> pub = (Map<String, Object>) micro.get("micro.publish.subscribers1");
        if (pub == null) {
            System.err.println("self-test: no micro.publish.subscribers1 in " + anyResult);
            return EXIT_MISSING;
        }
        pub.put("nsPerOp", num(pub.get("nsPerOp")) * 2.0);
        pub.put("p50", num(pub.get("p50")) * 2.0);
        pub.put("p99", num(pub.get("p99")) * 2.0);

        int noOp = compare(base, base, 0.15, true);
        int slowed = compare(base, mutated, 0.15, true);

        Map<String, Object> stripped = Json.parseObject(Json.write(base));
        Map<String, Object> strippedMicro = (Map<String, Object>) stripped.get("micro");
        strippedMicro.remove("micro.publish.subscribers1");
        int missing = compare(base, stripped, 0.15, true);

        System.out.println("compare self-test:");
        System.out.println("  no-op run exit code        = " + noOp + " (expect 0)");
        System.out.println("  2x publish slowdown exit   = " + slowed + " (expect 1)");
        System.out.println("  missing metric exit code   = " + missing + " (expect 2)");
        boolean pass = noOp == EXIT_OK && slowed == EXIT_REGRESSION && missing == EXIT_MISSING;
        System.out.println(pass ? "SELF-TEST PASS" : "SELF-TEST FAIL");
        return pass ? EXIT_OK : EXIT_REGRESSION;
    }
}
