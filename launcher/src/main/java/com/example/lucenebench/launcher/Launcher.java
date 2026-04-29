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
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * Interactive console launcher for the Lucene directory benchmark.
 *
 * <p>The launcher prompts the operator for the desired Lucene version (4.10.4,
 * 9.10.0, or both), the user count, the directory implementation under test,
 * and the workload mode, then spawns the matching shaded jar(s) using {@link
 * ProcessBuilder} so each Lucene version runs in its own JVM with an isolated
 * classpath. This is necessary because Lucene 4 and Lucene 9 share the
 * {@code org.apache.lucene} package and cannot coexist in a single classloader.
 */
public final class Launcher {

    private static final String LUCENE4_JAR = "lucene4-bench/target/lucene4-bench.jar";
    private static final String LUCENE9_JAR = "lucene9-bench/target/lucene9-bench.jar";

    public static void main(String[] args) throws Exception {
        // Non-interactive bypass: `--auto <version> <users> <dir> <mode>` for scripted runs.
        if (args.length > 0 && "--auto".equals(args[0])) {
            runAuto(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

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

            List<String> jvmArgs = mmapJvmArgs();
            if (versionChoice == 1 || versionChoice == 3) {
                runJar(LUCENE4_JAR, jvmArgs, users, dirArg, modeArg, threads, "Lucene 4.10.4");
            }
            if (versionChoice == 2 || versionChoice == 3) {
                runJar(LUCENE9_JAR, jvmArgs, users, dirArg, modeArg, threads, "Lucene 9.10.0");
            }
            System.out.println();
            System.out.println(" All requested benchmarks completed.");
        }
    }

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
        List<String> jvmArgs = mmapJvmArgs();
        if (versionChoice == 1 || versionChoice == 3) {
            runJar(LUCENE4_JAR, jvmArgs, users, dirArg, modeArg, threads, "Lucene 4.10.4");
        }
        if (versionChoice == 2 || versionChoice == 3) {
            runJar(LUCENE9_JAR, jvmArgs, users, dirArg, modeArg, threads, "Lucene 9.10.0");
        }
    }

    /**
     * Lucene 9's MMapDirectory uses {@code sun.misc.Unsafe} reflectively on Java 11–17
     * to forcibly unmap byte buffers when an index is closed. Without an
     * {@code --add-opens} hint these reflective accesses log warnings; the benchmark
     * still works, but the warnings clutter output. Lucene 4 ignores these flags.
     */
    private static List<String> mmapJvmArgs() {
        List<String> a = new ArrayList<>();
        a.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        a.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        return a;
    }

    private static void runJar(String relativeJar, List<String> jvmArgs, int users, String dirArg,
                               String modeArg, int threads, String label) throws IOException, InterruptedException {
        Path jar = locateJar(relativeJar);
        if (jar == null) {
            System.err.println();
            System.err.println("ERROR: cannot find " + relativeJar);
            System.err.println("Build the project first:  mvn -q -DskipTests package");
            System.exit(3);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.addAll(jvmArgs);
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
        // Stream subprocess output to the launcher console so users see live progress.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            System.err.println(" " + label + " exited with code " + rc);
        }
    }

    /**
     * Finds the shaded jar relative to the current working directory. Walks up a
     * couple of levels so the launcher works whether invoked from the project
     * root or from {@code launcher/target/}.
     */
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

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path bin = Paths.get(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isExecutable(bin) ? bin.toString() : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
