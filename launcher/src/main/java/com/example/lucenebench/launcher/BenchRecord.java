package com.example.lucenebench.launcher;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code BENCH-RESULT} line emitted by a benchmark child JVM. The
 * benchmark prints these lines as:
 *
 * <pre>
 * BENCH-RESULT lucene=9.10.0 java=17.0.18 dir=MMapDirectory section=search n=25 \
 *              avgMs=5.237 p95Ms=10.053 p99Ms=10.053 maxMs=10.053 totalMs=130.92
 * </pre>
 *
 * <p>The {@link Launcher} captures and aggregates them when running the
 * cross-JDK matrix.
 */
final class BenchRecord {

    /** Pattern that captures every key=value pair on a BENCH-RESULT line. */
    private static final Pattern KV = Pattern.compile("(\\w+)=([^\\s]+)");

    final String lucene;
    final String javaVersion;
    final String dir;
    final String section;
    final int n;
    final double avgMs;
    final double p95Ms;
    final double p99Ms;
    final double maxMs;
    final double totalMs;

    private BenchRecord(String lucene, String javaVersion, String dir, String section,
                        int n, double avgMs, double p95Ms, double p99Ms, double maxMs, double totalMs) {
        this.lucene = lucene;
        this.javaVersion = javaVersion;
        this.dir = dir;
        this.section = section;
        this.n = n;
        this.avgMs = avgMs;
        this.p95Ms = p95Ms;
        this.p99Ms = p99Ms;
        this.maxMs = maxMs;
        this.totalMs = totalMs;
    }

    /** Returns null if the line is malformed. */
    static BenchRecord parse(String line) {
        Map<String, String> kv = new HashMap<>();
        Matcher m = KV.matcher(line);
        while (m.find()) {
            kv.put(m.group(1), m.group(2));
        }
        try {
            return new BenchRecord(
                    require(kv, "lucene"),
                    require(kv, "java"),
                    require(kv, "dir"),
                    require(kv, "section"),
                    Integer.parseInt(require(kv, "n")),
                    parseD(kv, "avgMs"),
                    parseD(kv, "p95Ms"),
                    parseD(kv, "p99Ms"),
                    parseD(kv, "maxMs"),
                    parseD(kv, "totalMs"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return v;
    }

    private static double parseD(Map<String, String> kv, String key) {
        return Double.parseDouble(require(kv, key));
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "BenchRecord{lucene=%s java=%s dir=%s section=%s avgMs=%.3f}",
                lucene, javaVersion, dir, section, avgMs);
    }
}
