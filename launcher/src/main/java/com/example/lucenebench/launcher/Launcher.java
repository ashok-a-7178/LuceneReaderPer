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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeSet;

/**
 * Interactive console launcher for the Lucene directory benchmark.
 *
 * <p>The launcher prompts the operator for the desired Lucene version (4.10.4,
 * 9.10.0, or both), the user count, the directory implementation under test,
 * and the workload mode, then spawns the matching shaded jar(s) using {@link
 * ProcessBuilder} so each Lucene version runs in its own JVM with an isolated
 * classpath. This is necessary because Lucene 4 and Lucene 9 share the
 * {@code org.apache.lucene} package and cannot coexist in a single classloader.
 *
 * <p>The launcher additionally supports a {@code --matrix} mode that runs the
 * same benchmark across <b>multiple JDK installations</b> (e.g. Java 11 / 17 /
 * 21 / 25), with Lucene-tuned JVM and GC flags applied to every spawned child,
 * and prints a consolidated cross-JDK comparison table at the end.
 */
public final class Launcher {

    private static final String LUCENE4_JAR = "lucene4-bench/target/lucene4-bench.jar";
    private static final String LUCENE9_JAR = "lucene9-bench/target/lucene9-bench.jar";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--matrix".equals(args[0])) {
            runMatrix(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "--auto".equals(args[0])) {
            runAuto(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        runInteractive();
    }

    // ---------------------------------------------------------------- interactive

    private static void runInteractive() throws Exception {
        try (Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8)) {
            System.out.println("================================================================");
            System.out.println(" Lucene Directory Benchmark — NIOFSDirectory vs MMapDirectory");
            System.out.println("================================================================");
            System.out.println(" Choose Lucene version:");
            System.out.println("   1) Lucene 4.10.4");
            System.out.println("   2) Lucene 9.10.0");
            System.out.println("   3) Both");
            int versionChoice = readInt(sc, "Enter choice [1/2/3]: ", 1, 3);

            int users = readInt(sc, "Number of users (e.g. 100, 1000, 2000): ", 1, Integer.MAX_VALUE);

            System.out.println(" Directory implementation:");
            System.out.println("   1) NIOFSDirectory only");
            System.out.println("   2) MMapDirectory only");
            System.out.println("   3) Both (recommended for comparison)");
            int dirChoice = readInt(sc, "Enter choice [1/2/3]: ", 1, 3);
            String dirArg = dirChoice == 1 ? "NIOFS" : dirChoice == 2 ? "MMAP" : "BOTH";

            System.out.println(" Workload:");
            System.out.println("   1) Indexing only");
            System.out.println("   2) Indexing + Searching");
            int modeChoice = readInt(sc, "Enter choice [1/2]: ", 1, 2);
            String modeArg = modeChoice == 1 ? "INDEX" : "BOTH";

            int defaultThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
            int threads = readInt(sc,
                    "Concurrency level (threads) [default " + defaultThreads + "]: ",
                    1, 4096, defaultThreads);

            JdkInstall jdk = JdkInstall.current();
            if (versionChoice == 1 || versionChoice == 3) {
                runJar(LUCENE4_JAR, jdk, users, dirArg, modeArg, threads, "Lucene 4.10.4", null);
            }
            if (versionChoice == 2 || versionChoice == 3) {
                runJar(LUCENE9_JAR, jdk, users, dirArg, modeArg, threads, "Lucene 9.10.0", null);
            }
            System.out.println();
            System.out.println(" All requested benchmarks completed.");
        }
    }

    // ---------------------------------------------------------------- --auto

    private static void runAuto(String[] a) throws Exception {
        if (a.length < 4) {
            System.err.println("Usage: --auto <1|2|3> <users> <NIOFS|MMAP|BOTH> <INDEX|BOTH> [threads]");
            System.exit(2);
        }
        int versionChoice = Integer.parseInt(a[0]);
        int users = Integer.parseInt(a[1]);
        String dirArg = a[2].toUpperCase(Locale.ROOT);
        String modeArg = a[3].toUpperCase(Locale.ROOT);
        int threads = a.length > 4
                ? Integer.parseInt(a[4])
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        JdkInstall jdk = JdkInstall.current();
        if (versionChoice == 1 || versionChoice == 3) {
            runJar(LUCENE4_JAR, jdk, users, dirArg, modeArg, threads, "Lucene 4.10.4", null);
        }
        if (versionChoice == 2 || versionChoice == 3) {
            runJar(LUCENE9_JAR, jdk, users, dirArg, modeArg, threads, "Lucene 9.10.0", null);
        }
    }

    // ---------------------------------------------------------------- --matrix

    /**
     * <pre>
     * --matrix [users] [dir] [mode] [threads] [jdks-csv]
     * </pre>
     * Runs the benchmark for every (JDK × Lucene version) combination, applies
     * Lucene-tuned JVM/GC arguments to every child JVM, captures the
     * machine-readable BENCH-RESULT lines from each child, and prints a
     * consolidated cross-JDK comparison table.
     *
     * <p>If {@code jdks-csv} is omitted or the literal {@code auto}, the
     * launcher discovers JDKs from {@code JAVA_HOME_*_X64} env vars and the
     * common Linux locations under {@code /usr/lib/jvm/}.
     */
    private static void runMatrix(String[] a) throws Exception {
        int users = a.length > 0 ? Integer.parseInt(a[0]) : 50;
        String dirArg = (a.length > 1 ? a[1] : "BOTH").toUpperCase(Locale.ROOT);
        String modeArg = (a.length > 2 ? a[2] : "BOTH").toUpperCase(Locale.ROOT);
        int threads = a.length > 3
                ? Integer.parseInt(a[3])
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        String jdksSpec = a.length > 4 ? a[4] : "auto";

        List<JdkInstall> jdks = "auto".equalsIgnoreCase(jdksSpec)
                ? JdkInstall.discover()
                : JdkInstall.fromCsv(jdksSpec);

        if (jdks.isEmpty()) {
            System.err.println("No JDKs discovered. Set JAVA_HOME_* env vars, populate /usr/lib/jvm/, or pass a comma-separated list of JAVA_HOME paths.");
            System.exit(4);
        }

        System.out.println("================================================================");
        System.out.println(" Cross-JDK matrix benchmark");
        System.out.println("================================================================");
        System.out.printf(Locale.ROOT, " users=%d  directory=%s  mode=%s  threads=%d%n",
                users, dirArg, modeArg, threads);
        System.out.println(" JDKs:");
        for (JdkInstall j : jdks) {
            System.out.printf(Locale.ROOT, "   - %-8s -> %s%n", j.label(), j.home());
        }

        // Aggregated results: jdkLabel -> list of BENCH-RESULT records.
        Map<String, List<BenchRecord>> byJdk = new LinkedHashMap<>();

        for (JdkInstall jdk : jdks) {
            byJdk.put(jdk.label(), new ArrayList<>());
            // Lucene 4 may fail to run on very new JDKs (e.g. Java 25 removed
            // SecurityManager). Run it but tolerate non-zero exit.
            runJar(LUCENE4_JAR, jdk, users, dirArg, modeArg, threads,
                    "Lucene 4.10.4 on " + jdk.label(), byJdk.get(jdk.label()));
            runJar(LUCENE9_JAR, jdk, users, dirArg, modeArg, threads,
                    "Lucene 9.10.0 on " + jdk.label(), byJdk.get(jdk.label()));
        }

        printMatrixSummary(byJdk);
    }

    // ---------------------------------------------------------------- run subprocess

    /**
     * Launches a benchmark jar with Lucene-tuned JVM/GC options. If {@code sink}
     * is non-null the launcher captures every {@code BENCH-RESULT} line emitted
     * by the child for later aggregation.
     */
    private static void runJar(String relativeJar, JdkInstall jdk, int users, String dirArg,
                               String modeArg, int threads, String label,
                               List<BenchRecord> sink) throws IOException, InterruptedException {
        Path jar = locateJar(relativeJar);
        if (jar == null) {
            System.err.println();
            System.err.println("ERROR: cannot find " + relativeJar);
            System.err.println("Build the project first:  mvn -q -DskipTests package");
            System.exit(3);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.javaExecutable().toString());
        cmd.addAll(luceneTunedJvmArgs(jdk));
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
        cmd.add(Integer.toString(users));
        cmd.add(dirArg);
        cmd.add(modeArg);
        cmd.add(Integer.toString(threads));

        System.out.println();
        System.out.println("################################################################");
        System.out.println(" Running " + label);
        System.out.println(" cmd: " + String.join(" ", cmd));
        System.out.println("################################################################");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
                if (sink != null && line.startsWith("BENCH-RESULT ")) {
                    BenchRecord rec = BenchRecord.parse(line);
                    if (rec != null) sink.add(rec);
                }
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println(" " + label + " exited with code " + rc + " (continuing)");
        }
    }

    /**
     * Lucene-tuned JVM/GC arguments applied to every spawned child JVM.
     *
     * <p>References:
     * <ul>
     *   <li><b>{@code --add-opens=…}</b> — Lucene 9 {@code MMapDirectory} uses
     *       reflective access to {@code java.nio}, {@code sun.nio.ch}, and
     *       {@code jdk.internal.ref.Cleaner} to forcibly unmap closed segments.
     *       Without these opens the child logs warnings on Java 11–17 and
     *       fails outright on Java 21+ for {@code jdk.internal.ref}.</li>
     *   <li><b>{@code -XX:+UseG1GC}</b> + {@code -XX:MaxGCPauseMillis=200} —
     *       the GC profile recommended by the Lucene project for throughput
     *       with bounded pauses on indexing/search workloads.</li>
     *   <li><b>{@code -XX:+AlwaysPreTouch}</b> — pre-faults the heap so the
     *       first allocation in a measured section doesn't pay page-fault
     *       cost, giving cleaner per-section timings.</li>
     *   <li><b>{@code -XX:+UseStringDeduplication}</b> — useful because Lucene
     *       analyzers and field names produce many duplicate strings.</li>
     *   <li><b>{@code -Xms1g -Xmx1g}</b> — modest fixed heap so the OS page
     *       cache (which {@code MMapDirectory} relies on for fast reads)
     *       keeps as much physical memory as possible.</li>
     *   <li><b>{@code -XX:MaxDirectMemorySize=512m}</b> — bounds the off-heap
     *       NIO direct buffer pool used by {@code NIOFSDirectory}.</li>
     *   <li><b>{@code -Dorg.apache.lucene.store.MMapDirectory.enableMemorySegments=true}</b>
     *       — explicitly opts into Lucene 9's foreign-memory fast path on
     *       Java 19+; ignored on older runtimes.</li>
     * </ul>
     */
    private static List<String> luceneTunedJvmArgs(JdkInstall jdk) {
        List<String> a = new ArrayList<>();
        // Module access
        a.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        a.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        a.add("--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED");
        // Heap & off-heap sizing (kept modest so OS page cache wins)
        a.add("-Xms1g");
        a.add("-Xmx1g");
        a.add("-XX:MaxDirectMemorySize=512m");
        // GC tuning
        a.add("-XX:+UseG1GC");
        a.add("-XX:MaxGCPauseMillis=200");
        a.add("-XX:+AlwaysPreTouch");
        a.add("-XX:+UseStringDeduplication");
        // Lucene 9 MemorySegment fast path on Java 19+
        a.add("-Dorg.apache.lucene.store.MMapDirectory.enableMemorySegments=true");
        // Java 18–24 require an explicit opt-in to allow SecurityManager use,
        // which Lucene 4 still references via privileged blocks. Java 25 has
        // fully removed the API and rejects the flag entirely, so we omit it
        // there. On Java 11–17 the default policy already allows it.
        if (jdk.featureVersion() >= 18 && jdk.featureVersion() <= 24) {
            a.add("-Djava.security.manager=allow");
        }
        return a;
    }

    /**
     * Prints a per-section table that lists, for every (Lucene version,
     * directory, JDK) cell, the average and p95 in ms. The very last column
     * picks the fastest JDK for that row so the operator can immediately see
     * which ecosystem wins each section.
     */
    private static void printMatrixSummary(Map<String, List<BenchRecord>> byJdk) {
        // Flatten into a map: key -> per-JDK avgMs.
        // Key = lucene + "|" + dir + "|" + section
        Map<String, Map<String, BenchRecord>> table = new LinkedHashMap<>();
        TreeSet<String> jdkLabels = new TreeSet<>(JdkInstall.LABEL_COMPARATOR);
        for (Map.Entry<String, List<BenchRecord>> e : byJdk.entrySet()) {
            jdkLabels.add(e.getKey());
            for (BenchRecord r : e.getValue()) {
                String k = r.lucene + "|" + r.dir + "|" + r.section;
                table.computeIfAbsent(k, __ -> new LinkedHashMap<>()).put(e.getKey(), r);
            }
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.println(" Cross-JDK matrix — avg ms per section (lower is better)");
        System.out.println("================================================================");

        // Header
        StringBuilder header = new StringBuilder();
        header.append(String.format(Locale.ROOT, "%-9s %-16s %-13s", "lucene", "directory", "section"));
        for (String j : jdkLabels) {
            header.append(String.format(Locale.ROOT, " %12s", j));
        }
        header.append(String.format(Locale.ROOT, " %12s", "winner"));
        System.out.println(header);

        for (Map.Entry<String, Map<String, BenchRecord>> e : table.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            StringBuilder row = new StringBuilder();
            row.append(String.format(Locale.ROOT, "%-9s %-16s %-13s", parts[0], parts[1], parts[2]));
            String winner = null;
            double best = Double.POSITIVE_INFINITY;
            for (String j : jdkLabels) {
                BenchRecord r = e.getValue().get(j);
                if (r == null) {
                    row.append(String.format(Locale.ROOT, " %12s", "-"));
                } else {
                    row.append(String.format(Locale.ROOT, " %12.3f", r.avgMs));
                    if (r.avgMs < best) {
                        best = r.avgMs;
                        winner = j;
                    }
                }
            }
            row.append(String.format(Locale.ROOT, " %12s", winner == null ? "-" : winner));
            System.out.println(row);
        }
        // -------- direct NIOFS vs MMap verdict per (Lucene version × JDK) --------
        // For each (lucene, jdk) pair, compare NIOFSDirectory vs MMapDirectory
        // for the two sections users actually care about: indexing and search.
        // The winner column shows which directory is faster at concurrent
        // index / search on that JVM, with the magnitude of the speed-up.
        System.out.println();
        System.out.println("================================================================");
        System.out.println(" NIOFSDirectory vs MMapDirectory verdict (concurrent index + search)");
        System.out.println("================================================================");
        System.out.printf(Locale.ROOT, "%-9s %-8s %-9s %12s %12s %-10s %8s%n",
                "lucene", "jdk", "section", "NIOFS(ms)", "MMAP(ms)", "winner", "delta");
        // Collect the lucene versions and jdks present in results.
        TreeSet<String> luceneVersions = new TreeSet<>();
        for (String key : table.keySet()) luceneVersions.add(key.split("\\|", -1)[0]);
        String[] sections = {"indexing", "search"};
        for (String lucene : luceneVersions) {
            for (String section : sections) {
                Map<String, BenchRecord> nioRow = table.get(lucene + "|NIOFSDirectory|" + section);
                Map<String, BenchRecord> mmapRow = table.get(lucene + "|MMapDirectory|" + section);
                if (nioRow == null || mmapRow == null) continue;
                for (String jdk : jdkLabels) {
                    BenchRecord n = nioRow.get(jdk);
                    BenchRecord m = mmapRow.get(jdk);
                    if (n == null || m == null) continue;
                    String winner;
                    double delta;
                    if (n.avgMs < m.avgMs) {
                        winner = "NIOFS";
                        delta = (m.avgMs - n.avgMs) / m.avgMs * 100.0;
                    } else {
                        winner = "MMAP";
                        delta = (n.avgMs - m.avgMs) / n.avgMs * 100.0;
                    }
                    System.out.printf(Locale.ROOT, "%-9s %-8s %-9s %12.3f %12.3f %-10s %7.1f%%%n",
                            lucene, jdk, section, n.avgMs, m.avgMs, winner, delta);
                }
            }
        }
        System.out.println();
        System.out.println(" Tip: re-run with a larger user count for steadier numbers.");
    }

    // ---------------------------------------------------------------- helpers

    private static Path locateJar(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            Path parent = cwd.getParent();
            if (parent == null) break;
            cwd = parent;
        }
        return null;
    }

    private static int readInt(Scanner sc, String prompt, int min, int max) {
        return readInt(sc, prompt, min, max, Integer.MIN_VALUE);
    }

    private static int readInt(Scanner sc, String prompt, int min, int max, int defaultVal) {
        while (true) {
            System.out.print(prompt);
            String line = sc.hasNextLine() ? sc.nextLine().trim() : "";
            if (line.isEmpty() && defaultVal != Integer.MIN_VALUE) return defaultVal;
            try {
                int v = Integer.parseInt(line);
                if (v < min || v > max) {
                    System.out.println("  must be in [" + min + "," + max + "]");
                    continue;
                }
                return v;
            } catch (NumberFormatException nfe) {
                System.out.println("  not a number, try again");
            }
        }
    }
}
