package com.aaravlabs.synapse.bench.shared;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environment metadata recorded with every result so numbers are interpretable.
 */
public final class Env {

    private Env() {
    }

    public static Map<String, String> collect(String mode, long seed, int forks, int rounds) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        m.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        m.put("cpu", cpuModel());
        m.put("gitSha", gitSha());
        m.put("timestamp", java.time.Instant.now().toString());
        m.put("mode", mode);
        m.put("seed", Long.toString(seed));
        m.put("forks", Integer.toString(forks));
        m.put("rounds", Integer.toString(rounds));
        return m;
    }

    private static String cpuModel() {
        try {
            Path p = Paths.get("/proc/cpuinfo");
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        int colon = line.indexOf(':');
                        if (colon >= 0) return line.substring(colon + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return System.getProperty("os.arch", "unknown");
    }

    private static String gitSha() {
        try {
            Process proc = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (proc.waitFor() == 0 && line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }
}
