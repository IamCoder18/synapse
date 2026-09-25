package com.aaravlabs.synapse.bench.harness;

import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.OrchestratorImpl;
import com.aaravlabs.synapse.bench.shared.World;
import com.aaravlabs.synapse.ftc.GamepadAdaptor;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.seattlesolvers.solverslib.command.CommandScheduler;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gate 1 + gate 3: runtime class-provenance assertions and the structural review
 * rule. Measured framework classes must load from the real project output, the
 * extracted published SolversLib AAR, and the checked-in FTC SDK stub — never
 * from {@code benchmarks/build/classes} (which would mean a vendored
 * reimplementation).
 */
public final class FrameworkProvenance {

    public static final class Result {
        public final boolean ok;
        public final List<String> messages;

        Result(boolean ok, List<String> messages) {
            this.ok = ok;
            this.messages = messages;
        }
    }

    private FrameworkProvenance() {
    }

    public static Result verify() {
        List<String> messages = new ArrayList<>();
        boolean ok = true;

        ok &= check(CommandScheduler.class, "solverslib/classes.jar",
                "benchmarks/build/classes", messages);
        ok &= check(OrchestratorImpl.class, null,
                "benchmarks/build/classes", messages);
        ok &= check(Gamepad.class, "ftc-sdk-stub.jar",
                "benchmarks/build/classes", messages);
        ok &= check(GamepadAdaptor.class, null,
                "benchmarks/build/classes", messages);

        URL orchSource = OrchestratorImpl.class.getProtectionDomain().getCodeSource().getLocation();
        URL benchSource = FrameworkProvenance.class.getProtectionDomain().getCodeSource().getLocation();
        if (orchSource != null && orchSource.equals(benchSource)) {
            ok = false;
            messages.add("FAIL OrchestratorImpl resolved from benchmarks output: " + orchSource);
        } else {
            messages.add("OK   OrchestratorImpl from " + orchSource);
        }

        // Touch real dispatch so lazy linkage of the measured path is exercised.
        try {
            World world = new World(com.aaravlabs.synapse.bench.shared.Scenario.S0_MinimalDrive, 1,
                    new com.aaravlabs.synapse.bench.shared.Metrics());
            com.aaravlabs.synapse.Orchestrator orch =
                    com.aaravlabs.synapse.Orchestrator.create("provenance", com.aaravlabs.synapse.LogSink.SILENT);
            orch.registerNode("probe", new Node(orch) {
            });
            GamepadAdaptor.attach(orch, world.gamepad1(), "g1");
            orch.publish("smoke", 1.0);
            CommandScheduler.getInstance().run();
            orch.close();
            world.close();
            messages.add("OK   smoke dispatch (Orchestrator + GamepadAdaptor + CommandScheduler) ran");
        } catch (Throwable t) {
            ok = false;
            messages.add("FAIL smoke dispatch: " + t);
        }

        return new Result(ok, messages);
    }

    private static boolean check(Class<?> cls, String mustContain, String mustNotContain,
                                 List<String> messages) {
        URL loc = cls.getProtectionDomain().getCodeSource().getLocation();
        String s = loc == null ? "null" : loc.toString();
        boolean ok = true;
        if (mustContain != null && !s.contains(mustContain)) {
            ok = false;
        }
        if (mustNotContain != null && s.contains(mustNotContain)) {
            ok = false;
        }
        messages.add((ok ? "OK   " : "FAIL ") + cls.getName() + " <- " + s);
        return ok;
    }

    /**
     * Gate 3 (structural review rule): {@code shared/} must contain zero Synapse
     * or SolversLib dispatch types, and style packages must contain zero classes
     * named like {@code *Scheduler}, {@code *Orchestrator}, {@code *Bus}.
     */
    public static Result verifyStructure(Path sourceRoot) {
        List<String> messages = new ArrayList<>();
        boolean ok = true;
        Path shared = sourceRoot.resolve("com/aaravlabs/synapse/bench/shared");
        List<String> styleDirs = new ArrayList<>();
        styleDirs.add("raw");
        styleDirs.add("rawmt");
        styleDirs.add("solverslib");
        styleDirs.add("synapse");

        try (Stream<Path> files = Files.walk(shared)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.toString().endsWith(".java")) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (containsFrameworkDispatchImport(text)) {
                    ok = false;
                    messages.add("FAIL shared/ references a framework dispatch type: " + p.getFileName());
                }
            }
        } catch (Exception e) {
            ok = false;
            messages.add("FAIL structure scan: " + e);
        }

        for (String dir : styleDirs) {
            Path base = sourceRoot.resolve("com/aaravlabs/synapse/bench/" + dir);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".java")) continue;
                    String simple = name.substring(0, name.length() - 5);
                    if (simple.endsWith("Scheduler") || simple.endsWith("Orchestrator") || simple.endsWith("Bus")) {
                        ok = false;
                        messages.add("FAIL style class looks like a reimplemented dispatch type: " + dir + "/" + name);
                    }
                }
            } catch (Exception e) {
                ok = false;
                messages.add("FAIL structure scan " + dir + ": " + e);
            }
        }

        if (ok) {
            messages.add("OK   shared/ has no framework dispatch types; style packages have no *Scheduler/*Orchestrator/*Bus");
        }
        return new Result(ok, messages);
    }

    public static Path defaultSourceRoot() {
        File f = new File(FrameworkProvenance.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath());
        // .../benchmarks/build/classes/java/main -> benchmarks/src/main/java
        Path p = f.toPath();
        for (int i = 0; i < 6 && p != null; i++) {
            Path candidate = p.resolve(Paths.get("src", "main", "java"));
            if (Files.isDirectory(candidate.resolve("com/aaravlabs/synapse/bench/shared"))) {
                return candidate;
            }
            p = p.getParent();
        }
        return Paths.get("benchmarks", "src", "main", "java");
    }

    /**
     * True if the source references a Synapse or SolversLib dispatch type. The
     * benchmark's own {@code com.aaravlabs.synapse.bench.*} packages are allowed.
     */
    static boolean containsFrameworkDispatchImport(String text) {
        String[] banned = {
                "com.aaravlabs.synapse.Orchestrator",
                "com.aaravlabs.synapse.Node",
                "com.aaravlabs.synapse.Topic",
                "com.aaravlabs.synapse.Subscription",
                "com.aaravlabs.synapse.MessageHandler",
                "com.aaravlabs.synapse.LogSink",
                "com.aaravlabs.synapse.internal",
                "com.aaravlabs.synapse.ftc",
                "com.aaravlabs.synapse.annotation",
                "com.seattlesolvers.solverslib"
        };
        for (String b : banned) {
            if (text.contains(b)) return true;
        }
        return false;
    }
}
