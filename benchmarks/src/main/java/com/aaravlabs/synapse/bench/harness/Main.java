package com.aaravlabs.synapse.bench.harness;

import com.aaravlabs.synapse.bench.shared.Env;
import com.aaravlabs.synapse.bench.shared.Hist;
import com.aaravlabs.synapse.bench.shared.Metrics;
import com.aaravlabs.synapse.bench.shared.PairRunner;
import com.aaravlabs.synapse.bench.shared.Scenario;
import com.aaravlabs.synapse.bench.shared.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single entrypoint for the benchmark suite.
 *
 * <pre>
 * run --quick|--full [--scenarios S0,S3] [--styles raw,solverslib,synapse]
 *     [--forks N] [--alloc] [--seed N]
 * compare results/baseline.json results/latest.json [--tolerance 0.15]
 * compare --self-test
 * gates --only framework,structure,mock-budget
 * </pre>
 */
public final class Main {

    private static final class Config {
        boolean full;
        boolean alloc;
        int forks = 1;
        int rounds = 1;
        long seed = 42;
        List<Scenario> scenarios = new ArrayList<>();
        List<String> styles = new ArrayList<>();
        Path outDir = Paths.get("results");
        String internalForkOut;

        long warmupMs() {
            return full ? 2000 : 800;
        }

        long measureMs() {
            return full ? 15_000 : 3000;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(Compare.EXIT_MISSING);
        }
        String cmd = args[0];
        switch (cmd) {
            case "run":
                System.exit(cmdRun(args));
                break;
            case "compare":
                System.exit(cmdCompare(args));
                break;
            case "gates":
                System.exit(cmdGates(args));
                break;
            default:
                usage();
                System.exit(Compare.EXIT_MISSING);
        }
    }

    private static void usage() {
        System.err.println("usage: run --quick|--full [--scenarios S0,S1,S2,S3] [--styles raw,rawmt,solverslib,synapse]");
        System.err.println("           [--forks N] [--alloc] [--seed N] [--out DIR]");
        System.err.println("       compare <baseline.json> <latest.json> [--tolerance 0.15]");
        System.err.println("       compare --self-test [results/latest.json]");
        System.err.println("       gates --only framework,structure,mock-budget [--quick|--full]");
    }

    // ---------------------------------------------------------------- run

    private static int cmdRun(String[] args) throws Exception {
        Config cfg = parseRunArgs(args);
        if (cfg.scenarios.isEmpty()) {
            for (Scenario s : Scenario.values()) cfg.scenarios.add(s);
        }

        if (cfg.internalForkOut != null) {
            Map<String, Object> doc = runSuite(cfg);
            Report.writeJson(Paths.get(cfg.internalForkOut), doc);
            return Compare.EXIT_OK;
        }

        if (cfg.forks > 1) {
            List<Map<String, Object>> forks = new ArrayList<>();
            for (int i = 0; i < cfg.forks; i++) {
                Path forkOut = cfg.outDir.resolve("fork-" + i + ".json");
                List<String> child = new ArrayList<>();
                child.add(System.getProperty("java.home") + "/bin/java");
                child.add("-Xms128m");
                child.add("-Xmx256m");
                child.add("-Xmn16m");
                child.add("-cp");
                child.add(System.getProperty("java.class.path"));
                child.add(Main.class.getName());
                child.add("run");
                child.add(cfg.full ? "--full" : "--quick");
                child.add("--rounds");
                child.add(Integer.toString(cfg.rounds));
                child.add("--seed");
                child.add(Long.toString(cfg.seed));
                child.add("--out");
                child.add(cfg.outDir.toString());
                child.add("--internal-fork-out");
                child.add(forkOut.toString());
                if (cfg.alloc) child.add("--alloc");
                if (!cfg.scenarios.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Scenario s : cfg.scenarios) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(s.name());
                    }
                    child.add("--scenarios");
                    child.add(sb.toString());
                }
                if (!cfg.styles.isEmpty()) {
                    child.add("--styles");
                    child.add(String.join(",", cfg.styles));
                }
                System.out.println("fork " + (i + 1) + "/" + cfg.forks + " ...");
                Process p = new ProcessBuilder(child).inheritIO().start();
                if (p.waitFor() != 0) {
                    System.err.println("fork " + i + " failed");
                    return Compare.EXIT_MISSING;
                }
                forks.add(Json.parseObject(
                        new String(Files.readAllBytes(forkOut), StandardCharsets.UTF_8)));
            }
            Map<String, Object> merged = mergeForks(forks, cfg);
            writeOutputs(cfg, merged);
            printLadder(merged);
            return gateExit(merged);
        }

        Map<String, Object> doc = runSuite(cfg);
        writeOutputs(cfg, doc);
        printLadder(doc);
        return gateExit(doc);
    }

    private static void writeOutputs(Config cfg, Map<String, Object> doc) throws IOException {
        Path json = cfg.outDir.resolve("latest.json");
        Path md = cfg.outDir.resolve("latest.md");
        Report.writeJson(json, doc);
        Report.writeMarkdown(md, doc);
        System.out.println("wrote " + json.toAbsolutePath());
        System.out.println("wrote " + md.toAbsolutePath());
    }

    private static int gateExit(Map<String, Object> doc) {
        @SuppressWarnings("unchecked")
        Map<String, Object> gates = (Map<String, Object>) doc.get("gates");
        Object ok = gates.get("ok");
        return Boolean.TRUE.equals(ok) ? Compare.EXIT_OK : Compare.EXIT_REGRESSION;
    }

    private static Map<String, Object> runSuite(Config cfg) throws Exception {
        FrameworkProvenance.Result provenance = FrameworkProvenance.verify();
        FrameworkProvenance.Result structure =
                FrameworkProvenance.verifyStructure(FrameworkProvenance.defaultSourceRoot());

        List<Report.Pair> pairs = new ArrayList<>();
        for (Scenario scenario : cfg.scenarios) {
            List<String> styles = cfg.styles.isEmpty()
                    ? Registry.stylesFor(scenario)
                    : filterStyles(Registry.stylesFor(scenario), cfg.styles);
            for (String style : styles) {
                System.out.println("=== " + scenario.name() + " x " + style + " ===");
                List<Report.Pair> rounds = new ArrayList<>();
                for (int r = 0; r < cfg.rounds; r++) {
                    rounds.add(runPair(scenario, style, cfg));
                }
                pairs.add(medianPair(rounds));
            }
        }

        MicroBench micro = new MicroBench(cfg.full);
        Map<String, MicroBench.Result> micros = micro.runAll();
        double budgetPct = MicroBench.mockBudgetRatioPct(micros);
        boolean budgetOk = !Double.isNaN(budgetPct) && budgetPct < 5.0;

        Map<String, Object> microMap = new LinkedHashMap<>();
        for (Map.Entry<String, MicroBench.Result> e : micros.entrySet()) {
            MicroBench.Result r = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nsPerOp", r.nsPerOp);
            m.put("p50", r.p50);
            m.put("p99", r.p99);
            m.put("count", r.count);
            microMap.put(e.getKey(), m);
        }

        Map<String, Object> gates = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>();
        messages.addAll(provenance.messages);
        messages.addAll(structure.messages);
        messages.add((budgetOk ? "OK   " : "FAIL ") + String.format(Locale.ROOT,
                "mock budget: micro.sim.deviceWrite = %.1f%% of smallest framework dispatch (limit 5%%)",
                budgetPct));
        gates.put("frameworkClasses", provenance.ok);
        gates.put("structure", structure.ok);
        gates.put("mockBudget", budgetOk);
        gates.put("mockBudgetRatioPct", budgetPct);
        gates.put("ok", provenance.ok && structure.ok && budgetOk);
        gates.put("messages", messages);

        Map<String, String> env = Env.collect(cfg.full ? "full" : "quick", cfg.seed, cfg.forks, cfg.rounds);
        return Report.document(env, pairs, microMap, gates);
    }

    private static Report.Pair runPair(Scenario scenario, String style, Config cfg) throws Exception {
        Metrics metrics = new Metrics();
        metrics.setAllocEnabled(cfg.alloc);
        World world = new World(scenario, cfg.seed + scenario.ordinal() * 17L, metrics,
                (cfg.warmupMs() + cfg.measureMs()) / 1000.0, Registry.drivePowerSign(style));
        PairRunner runner = Registry.create(scenario, style, world);
        world.start();
        runner.start();
        Thread.sleep(cfg.warmupMs());
        world.plant().resetTracking();
        metrics.startWindow();
        Thread.sleep(cfg.measureMs());
        metrics.endWindow();
        runner.stop();
        world.plant().freezeTracking();
        double liftRmse = world.plant().liftRmse();
        double headingRmse = world.plant().headingRmse();
        Hist.Snapshot latency = metrics.probeSnapshot("actuation");
        List<Metrics.TaskSnapshot> tasks = metrics.taskSnapshots();
        double loopHz = metrics.loopHz();
        double alloc = metrics.allocBytesPerSec();
        long gcCount = metrics.gcCount();
        long gcMillis = metrics.gcMillis();
        world.close();
        return Report.Pair.fromSnapshot(scenario.name(), style, latency, tasks,
                liftRmse, headingRmse, loopHz, alloc, gcCount, gcMillis);
    }

    private static List<String> filterStyles(List<String> available, List<String> wanted) {
        for (String w : wanted) {
            if (!available.contains(w)) {
                throw new IllegalArgumentException(
                        "requested style '" + w + "' is not available for this scenario");
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : available) {
            if (wanted.contains(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------- median-of-rounds/forks

    private static Report.Pair medianPair(List<Report.Pair> rounds) {
        if (rounds.size() == 1) return rounds.get(0);
        Report.Pair first = rounds.get(0);

        Map<String, Object> lat = new LinkedHashMap<>();
        for (String p : new String[] {"p50", "p90", "p99", "p999", "max", "min", "mean"}) {
            lat.put(p, medianOf(rounds, r -> num(((Map<?, ?>) r.latencyActuationNs).get(p))));
        }
        lat.put("count", (long) medianOf(rounds, r -> num(r.latencyActuationNs.get("count"))));

        Map<String, Object> rates = new LinkedHashMap<>();
        for (Object task : first.taskRates.keySet()) {
            String name = (String) task;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetHz", ((Map<?, ?>) first.taskRates.get(name)).get("targetHz"));
            entry.put("achievedHz", medianOf(rounds, r -> {
                Map<?, ?> t = (Map<?, ?>) r.taskRates.get(name);
                return t == null ? 0 : num(t.get("achievedHz"));
            }));
            entry.put("jitterP99Ns", medianOf(rounds, r -> {
                Map<?, ?> t = (Map<?, ?>) r.taskRates.get(name);
                return t == null ? 0 : num(t.get("jitterP99Ns"));
            }));
            entry.put("jitterP999Ns", medianOf(rounds, r -> {
                Map<?, ?> t = (Map<?, ?>) r.taskRates.get(name);
                return t == null ? 0 : num(t.get("jitterP999Ns"));
            }));
            entry.put("deadlineMissPct", medianOf(rounds, r -> {
                Map<?, ?> t = (Map<?, ?>) r.taskRates.get(name);
                return t == null ? 0 : num(t.get("deadlineMissPct"));
            }));
            entry.put("count", (long) medianOf(rounds, r -> {
                Map<?, ?> t = (Map<?, ?>) r.taskRates.get(name);
                return t == null ? 0 : num(t.get("count"));
            }));
            rates.put(name, entry);
        }

        Map<String, Object> tracking = new LinkedHashMap<>();
        tracking.put("liftRmse", medianOf(rounds, r -> num(r.trackingError.get("liftRmse"))));
        tracking.put("headingRmse", medianOf(rounds, r -> num(r.trackingError.get("headingRmse"))));

        double loopHz = medianOf(rounds, r -> r.loopHz);
        double alloc = medianOf(rounds, r -> r.allocBytesPerSec);
        long gcCount = (long) medianOf(rounds, r -> r.gcCount);
        long gcMillis = (long) medianOf(rounds, r -> r.gcMillis);
        return new Report.Pair(first.scenario, first.style, lat, rates, tracking, loopHz, alloc,
                gcCount, gcMillis);
    }

    private interface PairDouble {
        double get(Report.Pair p);
    }

    private static double medianOf(List<Report.Pair> pairs, PairDouble f) {
        double[] v = new double[pairs.size()];
        for (int i = 0; i < v.length; i++) v[i] = f.get(pairs.get(i));
        java.util.Arrays.sort(v);
        int n = v.length;
        return (n % 2 == 1) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeForks(List<Map<String, Object>> forks, Config cfg) {
        if (forks.size() == 1) return forks.get(0);
        Map<String, List<Report.Pair>> grouped = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> microGrouped = new LinkedHashMap<>();
        for (Map<String, Object> fork : forks) {
            for (Object o : (List<Object>) fork.get("scenarios")) {
                Report.Pair p = Report.Pair.fromMap((Map<String, Object>) o);
                grouped.computeIfAbsent(p.scenario + "/" + p.style, k -> new ArrayList<>()).add(p);
            }
            Map<String, Object> micro = (Map<String, Object>) fork.get("micro");
            for (Map.Entry<String, Object> e : micro.entrySet()) {
                microGrouped.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add((Map<String, Object>) e.getValue());
            }
        }
        List<Report.Pair> pairs = new ArrayList<>();
        for (List<Report.Pair> list : grouped.values()) {
            pairs.add(medianPair(list));
        }
        Map<String, Object> microOut = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : microGrouped.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nsPerOp", medianMaps(e.getValue(), "nsPerOp"));
            m.put("p50", medianMaps(e.getValue(), "p50"));
            m.put("p99", medianMaps(e.getValue(), "p99"));
            m.put("count", medianMaps(e.getValue(), "count"));
            microOut.put(e.getKey(), m);
        }
        double budgetPct = MicroBench.mockBudgetRatioPct(toMicroResults(microOut));
        boolean allFramework = true;
        boolean allStructure = true;
        for (Map<String, Object> fork : forks) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) fork.get("gates");
            allFramework &= Boolean.TRUE.equals(g.get("frameworkClasses"));
            allStructure &= Boolean.TRUE.equals(g.get("structure"));
        }
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("frameworkClasses", allFramework);
        gates.put("structure", allStructure);
        gates.put("mockBudget", !Double.isNaN(budgetPct) && budgetPct < 5.0);
        gates.put("mockBudgetRatioPct", budgetPct);
        gates.put("ok", allFramework && allStructure && !Double.isNaN(budgetPct) && budgetPct < 5.0);
        gates.put("messages", List.of("merged " + forks.size() + " forks (median)"));

        Map<String, String> env = Env.collect(cfg.full ? "full" : "quick", cfg.seed, cfg.forks, cfg.rounds);
        return Report.document(env, pairs, microOut, gates);
    }

    private static Map<String, MicroBench.Result> toMicroResults(Map<String, Object> microOut) {
        Map<String, MicroBench.Result> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : microOut.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            out.put(e.getKey(), new MicroBench.Result(e.getKey(),
                    num(m.get("nsPerOp")),
                    (long) num(m.get("p50")),
                    (long) num(m.get("p99")),
                    (long) num(m.get("count"))));
        }
        return out;
    }

    private static double medianMaps(List<Map<String, Object>> maps, String key) {
        double[] v = new double[maps.size()];
        for (int i = 0; i < v.length; i++) v[i] = num(maps.get(i).get(key));
        java.util.Arrays.sort(v);
        int n = v.length;
        return (n % 2 == 1) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    /**
     * Ladder-shape check (acceptance criterion): S0 raw latency must beat Synapse,
     * and S3 Synapse lift-RMSE and PIDF achieved-Hz must beat raw and SolversLib.
     */
    @SuppressWarnings("unchecked")
    private static void printLadder(Map<String, Object> doc) {
        Map<String, Report.Pair> index = new LinkedHashMap<>();
        for (Object o : (List<Object>) doc.get("scenarios")) {
            Report.Pair p = Report.Pair.fromMap((Map<String, Object>) o);
            index.put(p.scenario + "/" + p.style, p);
        }
        Report.Pair s0Raw = index.get(Scenario.S0_MinimalDrive.name() + "/raw");
        Report.Pair s0Syn = index.get(Scenario.S0_MinimalDrive.name() + "/synapse");
        Report.Pair s3Syn = index.get(Scenario.S3_HeavyRobot.name() + "/synapse");
        Report.Pair s3Raw = index.get(Scenario.S3_HeavyRobot.name() + "/raw");
        Report.Pair s3Sol = index.get(Scenario.S3_HeavyRobot.name() + "/solverslib");

        List<String> notes = new ArrayList<>();
        boolean ok = true;
        if (s0Raw != null && s0Syn != null) {
            double rawP50 = num(s0Raw.latencyActuationNs.get("p50"));
            double synP50 = num(s0Syn.latencyActuationNs.get("p50"));
            boolean pass = rawP50 < synP50;
            ok &= pass;
            notes.add((pass ? "OK   " : "FAIL ") + String.format(Locale.ROOT,
                    "S0 raw actuation p50 %.0f ns < synapse %.0f ns", rawP50, synP50));
        }
        if (s3Syn != null && s3Raw != null && s3Sol != null) {
            double synLift = num(s3Syn.trackingError.get("liftRmse"));
            double rawLift = num(s3Raw.trackingError.get("liftRmse"));
            double solLift = num(s3Sol.trackingError.get("liftRmse"));
            boolean passLift = synLift < rawLift && synLift < solLift;
            ok &= passLift;
            notes.add((passLift ? "OK   " : "FAIL ") + String.format(Locale.ROOT,
                    "S3 lift RMSE synapse %.3f < raw %.3f, solverslib %.3f", synLift, rawLift, solLift));

            double synHz = pidfHz(s3Syn);
            double rawHz = pidfHz(s3Raw);
            double solHz = pidfHz(s3Sol);
            boolean passHz = synHz > rawHz && synHz > solHz;
            ok &= passHz;
            notes.add((passHz ? "OK   " : "FAIL ") + String.format(Locale.ROOT,
                    "S3 lift PIDF achieved Hz synapse %.1f > raw %.1f, solverslib %.1f", synHz, rawHz, solHz));

            double synHead = num(s3Syn.trackingError.get("headingRmse"));
            double rawHead = num(s3Raw.trackingError.get("headingRmse"));
            double solHead = num(s3Sol.trackingError.get("headingRmse"));
            notes.add(String.format(Locale.ROOT,
                    "info S3 heading RMSE synapse %.5f, raw %.5f, solverslib %.5f", synHead, rawHead, solHead));
        }
        System.out.println("ladder shape:");
        for (String n : notes) System.out.println("  " + n);
        if (!ok) System.out.println("  LADDER SHAPE NOT REPRODUCED (see README interpretation guide)");
    }

    private static double pidfHz(Report.Pair p) {
        Map<?, ?> rates = p.taskRates;
        Object lift = rates.get("liftPidf");
        return lift == null ? 0 : num(((Map<?, ?>) lift).get("achievedHz"));
    }

    // ------------------------------------------------------------- compare

    private static int cmdCompare(String[] args) throws IOException {
        if (args.length >= 2 && "--self-test".equals(args[1])) {
            Path any = args.length >= 3
                    ? Paths.get(args[2])
                    : Paths.get("results", "latest.json");
            if (!Files.exists(any)) {
                System.err.println("self-test needs an existing result file: " + any);
                return Compare.EXIT_MISSING;
            }
            return Compare.selfTest(any);
        }
        if (args.length < 3) {
            usage();
            return Compare.EXIT_MISSING;
        }
        double tolerance = 0.15;
        for (int i = 3; i < args.length; i++) {
            if ("--tolerance".equals(args[i]) && i + 1 < args.length) {
                tolerance = Double.parseDouble(args[++i]);
            }
        }
        return Compare.run(Paths.get(args[1]), Paths.get(args[2]), tolerance, false);
    }

    // -------------------------------------------------------------- gates

    private static int cmdGates(String[] args) {
        String only = "framework,structure,mock-budget";
        boolean full = false;
        for (int i = 1; i < args.length; i++) {
            if ("--only".equals(args[i]) && i + 1 < args.length) only = args[++i];
            else if ("--full".equals(args[i])) full = true;
        }
        boolean ok = true;
        List<String> messages = new ArrayList<>();
        if (only.contains("framework")) {
            FrameworkProvenance.Result r = FrameworkProvenance.verify();
            ok &= r.ok;
            messages.addAll(r.messages);
        }
        if (only.contains("structure")) {
            FrameworkProvenance.Result r =
                    FrameworkProvenance.verifyStructure(FrameworkProvenance.defaultSourceRoot());
            ok &= r.ok;
            messages.addAll(r.messages);
        }
        if (only.contains("mock-budget")) {
            MicroBench micro = new MicroBench(full);
            Map<String, MicroBench.Result> all = micro.runAll();
            double pct = MicroBench.mockBudgetRatioPct(all);
            boolean budgetOk = !Double.isNaN(pct) && pct < 5.0;
            ok &= budgetOk;
            messages.add(String.format(Locale.ROOT,
                    "%s mock budget: micro.sim.deviceWrite=%.2f ns vs limit 5%% of smallest dispatch (%.2f%%)",
                    budgetOk ? "OK  " : "FAIL",
                    all.get("micro.sim.deviceWrite").nsPerOp, pct));
        }
        for (String m : messages) System.out.println(m);
        System.out.println(ok ? "GATES PASS" : "GATES FAIL");
        return ok ? Compare.EXIT_OK : Compare.EXIT_REGRESSION;
    }

    // --------------------------------------------------------------- args

    private static Config parseRunArgs(String[] args) {
        Config cfg = new Config();
        boolean sawMode = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--quick":
                    cfg.full = false;
                    cfg.rounds = 1;
                    sawMode = true;
                    break;
                case "--full":
                    cfg.full = true;
                    cfg.rounds = 3;
                    sawMode = true;
                    break;
                case "--scenarios":
                    for (String s : args[++i].split(",")) {
                        if (!s.trim().isEmpty()) cfg.scenarios.add(Scenario.parse(s));
                    }
                    break;
                case "--styles":
                    for (String s : args[++i].split(",")) {
                        if (!s.trim().isEmpty()) cfg.styles.add(s.trim());
                    }
                    break;
                case "--forks":
                    cfg.forks = Integer.parseInt(args[++i]);
                    break;
                case "--seed":
                    cfg.seed = Long.parseLong(args[++i]);
                    break;
                case "--alloc":
                    cfg.alloc = true;
                    break;
                case "--out":
                    cfg.outDir = Paths.get(args[++i]);
                    break;
                case "--internal-fork-out":
                    cfg.internalForkOut = args[++i];
                    break;
                case "--rounds":
                    cfg.rounds = Integer.parseInt(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown arg " + a);
            }
        }
        if (!sawMode) {
            cfg.full = false;
            cfg.rounds = 1;
        }
        return cfg;
    }
}
