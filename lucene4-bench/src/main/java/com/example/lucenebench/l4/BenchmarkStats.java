package com.example.lucenebench.l4;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe accumulator for timing samples (nanoseconds). Computes total / avg /
 * min / max / p50 / p95 / p99 over all recorded samples.
 *
 * <p>Used to summarise per-section measurements (writer open, indexing, writer close,
 * reader open, search, reader close) across many concurrent benchmark workers.
 */
public final class BenchmarkStats {

    private final String label;
    private final ConcurrentLinkedQueue<Long> samples = new ConcurrentLinkedQueue<>();

    public BenchmarkStats(String label) {
        this.label = label;
    }

    public void record(long nanos) {
        samples.add(nanos);
    }

    public String label() {
        return label;
    }

    public int count() {
        return samples.size();
    }

    public Summary summarize() {
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        if (arr.length == 0) {
            return new Summary(label, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        Arrays.sort(arr);
        long total = 0L;
        for (long v : arr) {
            total += v;
        }
        double avg = (double) total / arr.length;
        long min = arr[0];
        long max = arr[arr.length - 1];
        long p50 = arr[(int) Math.min(arr.length - 1, Math.round(0.50 * arr.length))];
        long p95 = arr[(int) Math.min(arr.length - 1, Math.round(0.95 * arr.length))];
        long p99 = arr[(int) Math.min(arr.length - 1, Math.round(0.99 * arr.length))];
        return new Summary(label, arr.length, total, avg, min, max, p50, p95, p99);
    }

    /** Immutable snapshot of computed statistics, all timings in nanoseconds. */
    public static final class Summary {
        public final String label;
        public final int count;
        public final long totalNs;
        public final double avgNs;
        public final long minNs;
        public final long maxNs;
        public final long p50Ns;
        public final long p95Ns;
        public final long p99Ns;

        Summary(String label, int count, long totalNs, double avgNs,
                long minNs, long maxNs, long p50Ns, long p95Ns, long p99Ns) {
            this.label = label;
            this.count = count;
            this.totalNs = totalNs;
            this.avgNs = avgNs;
            this.minNs = minNs;
            this.maxNs = maxNs;
            this.p50Ns = p50Ns;
            this.p95Ns = p95Ns;
            this.p99Ns = p99Ns;
        }

        public double avgMs() { return avgNs / 1_000_000.0; }
        public double totalMs() { return totalNs / 1_000_000.0; }
        public double minMs() { return minNs / 1_000_000.0; }
        public double maxMs() { return maxNs / 1_000_000.0; }
        public double p50Ms() { return p50Ns / 1_000_000.0; }
        public double p95Ms() { return p95Ns / 1_000_000.0; }
        public double p99Ms() { return p99Ns / 1_000_000.0; }
    }
}
