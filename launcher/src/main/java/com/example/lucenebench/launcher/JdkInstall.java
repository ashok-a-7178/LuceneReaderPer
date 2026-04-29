package com.example.lucenebench.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a JDK installation that can host one of the benchmark child JVMs.
 *
 * <p>Discovery order, from highest to lowest priority:
 * <ol>
 *   <li>An explicit comma-separated list of JAVA_HOME paths (passed via the
 *       launcher's {@code --matrix} arguments).</li>
 *   <li>{@code JAVA_HOME_<n>_X64} environment variables exported by GitHub
 *       Actions runners (e.g. {@code JAVA_HOME_11_X64}).</li>
 *   <li>Conventional layouts under {@code /usr/lib/jvm/} (Linux Temurin/OpenJDK
 *       packages).</li>
 *   <li>The JDK that hosts the launcher itself (used for the non-matrix modes).</li>
 * </ol>
 */
final class JdkInstall {

    /** Sort order for JDK labels: numeric ascending (java8 &lt; java11 &lt; java17 &lt; …). */
    static final Comparator<String> LABEL_COMPARATOR = Comparator.comparingInt(JdkInstall::parseFeature)
            .thenComparing(Comparator.naturalOrder());

    private final Path home;
    private final String label;
    private final int featureVersion;

    private JdkInstall(Path home, String label, int featureVersion) {
        this.home = home;
        this.label = label;
        this.featureVersion = featureVersion;
    }

    Path home() { return home; }
    String label() { return label; }
    int featureVersion() { return featureVersion; }

    Path javaExecutable() {
        Path bin = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        return Files.isExecutable(bin) ? bin : Paths.get("java");
    }

    /** The JDK that hosts the launcher itself. */
    static JdkInstall current() {
        Path home = Paths.get(System.getProperty("java.home"));
        int feat = featureFromVersion(System.getProperty("java.version"));
        return new JdkInstall(home, "java" + feat, feat);
    }

    /** Discover JDKs from env vars and conventional Linux locations. */
    static List<JdkInstall> discover() {
        Map<Path, JdkInstall> uniq = new LinkedHashMap<>();

        // 1) GitHub Actions style env vars: JAVA_HOME_<n>_X64
        Pattern p = Pattern.compile("^JAVA_HOME_(\\d+)_X64$");
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            Matcher m = p.matcher(e.getKey());
            if (!m.matches()) continue;
            Path home = Paths.get(e.getValue()).toAbsolutePath().normalize();
            if (!Files.isDirectory(home)) continue;
            int feat = Integer.parseInt(m.group(1));
            uniq.putIfAbsent(home, new JdkInstall(home, "java" + feat, feat));
        }

        // 2) /usr/lib/jvm/temurin-<n>-jdk-* and /usr/lib/jvm/java-<n>-* style
        Path linuxJvm = Paths.get("/usr/lib/jvm");
        if (Files.isDirectory(linuxJvm)) {
            try {
                Files.list(linuxJvm).forEach(dir -> {
                    if (!Files.isDirectory(dir)) return;
                    String name = dir.getFileName().toString();
                    Integer feat = featureFromDirName(name);
                    if (feat == null) return;
                    Path bin = dir.resolve("bin/java");
                    if (!Files.isExecutable(bin)) return;
                    Path abs = dir.toAbsolutePath().normalize();
                    uniq.putIfAbsent(abs, new JdkInstall(abs, "java" + feat, feat));
                });
            } catch (IOException ignored) {
                // best-effort discovery
            }
        }

        // 3) Probe each candidate to confirm via `java -version` and refine the feature.
        List<JdkInstall> verified = new ArrayList<>();
        for (JdkInstall j : uniq.values()) {
            int probed = probeFeature(j);
            if (probed > 0 && probed != j.featureVersion) {
                j = new JdkInstall(j.home, "java" + probed, probed);
            }
            if (probed > 0) verified.add(j);
        }
        // Sort by feature version, ascending, and skip JDKs older than 11
        // (Lucene 9.10.0 requires Java 11; Lucene 4 still works there too).
        verified.removeIf(j -> j.featureVersion() < 11);
        verified.sort(Comparator.comparingInt(JdkInstall::featureVersion));
        return verified;
    }

    /** Parse a comma-separated list of JAVA_HOME paths or "auto". */
    static List<JdkInstall> fromCsv(String csv) {
        if (csv == null || csv.isEmpty() || "auto".equalsIgnoreCase(csv)) {
            return discover();
        }
        List<JdkInstall> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trim = part.trim();
            if (trim.isEmpty()) continue;
            Path home = Paths.get(trim).toAbsolutePath().normalize();
            if (!Files.isDirectory(home)) {
                System.err.println("WARN: JAVA_HOME path does not exist: " + trim);
                continue;
            }
            int feat = featureFromDirName(home.getFileName().toString());
            if (feat <= 0) feat = -1;
            JdkInstall j = new JdkInstall(home, "java" + (feat > 0 ? feat : "?"), feat > 0 ? feat : 0);
            int probed = probeFeature(j);
            if (probed > 0) {
                j = new JdkInstall(home, "java" + probed, probed);
                out.add(j);
            } else {
                System.err.println("WARN: could not probe JDK at " + home);
            }
        }
        out.sort(Comparator.comparingInt(JdkInstall::featureVersion));
        return out;
    }

    // ---------- internals ----------

    private static Integer featureFromDirName(String name) {
        // Examples:
        //   temurin-11-jdk-amd64   -> 11
        //   temurin-25-jdk-amd64   -> 25
        //   java-17-openjdk-amd64  -> 17
        //   jdk-21.0.2             -> 21
        //   adoptopenjdk-8-hotspot -> 8
        Matcher m = Pattern.compile("(?:^|[^0-9])(\\d{1,2})(?:[^0-9]|$)").matcher(name);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 7 && v <= 99) return v;
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static int featureFromVersion(String version) {
        if (version == null) return 0;
        // "11.0.20", "17.0.8+7", "1.8.0_392"
        if (version.startsWith("1.")) {
            try { return Integer.parseInt(version.split("\\.")[1]); } catch (Exception e) { return 0; }
        }
        Matcher m = Pattern.compile("^(\\d+)").matcher(version);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Runs the candidate's {@code java -version} and parses the feature
     * release. {@code java -version} writes to stderr on every JDK.
     */
    private static int probeFeature(JdkInstall j) {
        try {
            ProcessBuilder pb = new ProcessBuilder(j.javaExecutable().toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String text = sb.toString();
            // Lines look like:  openjdk version "21.0.5" 2024-10-15
            //                   openjdk version "1.8.0_392"
            Matcher m = Pattern.compile("version \"([^\"]+)\"").matcher(text);
            if (m.find()) return featureFromVersion(m.group(1));
        } catch (IOException | InterruptedException ignored) { }
        return 0;
    }

    private static int parseFeature(String label) {
        Matcher m = Pattern.compile("(\\d+)").matcher(label == null ? "" : label);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
