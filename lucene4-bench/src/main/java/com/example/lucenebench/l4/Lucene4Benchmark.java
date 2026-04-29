package com.example.lucenebench.l4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Version;

/**
 * Concurrent benchmark of {@link NIOFSDirectory} vs {@link MMapDirectory}
 * implemented against Lucene <b>4.10.4</b>.
 *
 * <p>For a given number of unique "users" the benchmark creates one Lucene index per
 * user under a working directory, concurrently indexes a fixed sample document set
 * into every user index, then concurrently runs a sample query mix against every
 * user index. Each section (writer-open, indexing, writer-close, reader-open,
 * search, reader-close) is timed independently and aggregated into per-section
 * statistics so the impact of each Directory implementation can be compared
 * cleanly.
 */
public final class Lucene4Benchmark {

    /** Lexicon used to synthesise documents and queries. */
    private static final String[] LEXICON = {
            "lucene", "directory", "concurrent", "benchmark", "search",
            "indexing", "performance", "mmap", "niofs", "reader",
            "writer", "segment", "analyzer", "token", "query",
            "ashok", "user", "document", "field", "java"
    };

    /** Documents indexed per user. Keep modest so we benchmark the framework, not I/O volume. */
    private static final int DOCS_PER_USER = 50;
    /** Queries run per user during the search phase. */
    private static final int QUERIES_PER_USER = 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Lucene4Benchmark <numUsers> [NIOFS|MMAP|BOTH] [INDEX|SEARCH|BOTH] [threads]");
            System.exit(2);
        }
        int numUsers = Integer.parseInt(args[0]);
        String dirOpt = args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : "BOTH";
        String modeOpt = args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : "BOTH";
        int threads = args.length > 3
                ? Integer.parseInt(args[3])
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

        System.out.println();
        System.out.println("================================================================");
        System.out.println(" Lucene 4.10.4 concurrent directory benchmark");
        System.out.println("================================================================");
        System.out.printf(Locale.ROOT,
                " users=%d  directory=%s  mode=%s  threads=%d  docs/user=%d  queries/user=%d%n",
                numUsers, dirOpt, modeOpt, threads, DOCS_PER_USER, QUERIES_PER_USER);
        System.out.printf(Locale.ROOT,
                " java=%s  os=%s  cpu=%d%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors());

        Path workRoot = Files.createTempDirectory("lucene4-bench-");
        try {
            List<Result> results = new ArrayList<>();
            if ("NIOFS".equals(dirOpt) || "BOTH".equals(dirOpt)) {
                results.add(runOne("NIOFSDirectory", workRoot.resolve("niofs"), numUsers, threads, modeOpt, DirKind.NIOFS));
            }
            if ("MMAP".equals(dirOpt) || "BOTH".equals(dirOpt)) {
                results.add(runOne("MMapDirectory", workRoot.resolve("mmap"), numUsers, threads, modeOpt, DirKind.MMAP));
            }
            printComparison(results);
        } finally {
            deleteRecursively(workRoot.toFile());
        }
    }

    private enum DirKind { NIOFS, MMAP }

    /** Holds all per-section stats for one Directory implementation. */
    private static final class Result {
        final String name;
        final BenchmarkStats writerOpen = new BenchmarkStats("writer.open");
        final BenchmarkStats indexing   = new BenchmarkStats("indexing");
        final BenchmarkStats writerClose = new BenchmarkStats("writer.close");
        final BenchmarkStats readerOpen = new BenchmarkStats("reader.open");
        final BenchmarkStats search     = new BenchmarkStats("search");
        final BenchmarkStats readerClose = new BenchmarkStats("reader.close");
        long indexingWallNs;
        long searchingWallNs;
        long totalSearchHits;
        Result(String name) { this.name = name; }
    }

    private static Result runOne(String name, Path root, int numUsers, int threads,
                                 String modeOpt, DirKind kind) throws Exception {
        Files.createDirectories(root);
        Result r = new Result(name);

        boolean doIndex = "INDEX".equals(modeOpt) || "BOTH".equals(modeOpt);
        boolean doSearch = "SEARCH".equals(modeOpt) || "BOTH".equals(modeOpt);

        // For SEARCH-only we still need indexed data, so always index first.
        long t0 = System.nanoTime();
        runIndexing(root, numUsers, threads, kind, r);
        r.indexingWallNs = System.nanoTime() - t0;

        if (doSearch) {
            long s0 = System.nanoTime();
            runSearching(root, numUsers, threads, kind, r);
            r.searchingWallNs = System.nanoTime() - s0;
        }

        printResult(r, doIndex, doSearch);
        return r;
    }

    private static Directory openDirectory(DirKind kind, File path) throws IOException {
        return kind == DirKind.MMAP ? new MMapDirectory(path) : new NIOFSDirectory(path);
    }

    private static void runIndexing(Path root, int numUsers, int threads,
                                    DirKind kind, Result r) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failed = new AtomicInteger();
        try {
            for (int i = 0; i < numUsers; i++) {
                final int userId = i;
                pool.submit(() -> {
                    try {
                        indexOneUser(root, userId, kind, r);
                    } catch (Throwable t) {
                        failed.incrementAndGet();
                        System.err.println("[index user=" + userId + "] " + t);
                    }
                });
            }
        } finally {
            pool.shutdown();
            // Generous timeout; benchmarks should finish well within this.
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow();
                throw new IllegalStateException("Indexing pool did not terminate");
            }
        }
        if (failed.get() > 0) {
            System.err.println("WARN: indexing failures = " + failed.get());
        }
    }

    private static void indexOneUser(Path root, int userId, DirKind kind, Result r) throws IOException {
        File path = root.resolve("user-" + userId).toFile();
        Analyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig cfg = new IndexWriterConfig(Version.LUCENE_4_10_4, analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        long t0 = System.nanoTime();
        Directory dir = openDirectory(kind, path);
        IndexWriter writer = new IndexWriter(dir, cfg);
        r.writerOpen.record(System.nanoTime() - t0);

        long t1 = System.nanoTime();
        Random rng = new Random(0xC0FFEEL ^ userId);
        for (int d = 0; d < DOCS_PER_USER; d++) {
            Document doc = new Document();
            doc.add(new StringField("user", "user-" + userId, Field.Store.YES));
            doc.add(new StringField("docId", "doc-" + d, Field.Store.YES));
            doc.add(new TextField("content", randomText(rng, 32), Field.Store.NO));
            writer.addDocument(doc);
        }
        writer.commit();
        r.indexing.record(System.nanoTime() - t1);

        long t2 = System.nanoTime();
        writer.close();
        dir.close();
        r.writerClose.record(System.nanoTime() - t2);
    }

    private static void runSearching(Path root, int numUsers, int threads,
                                     DirKind kind, Result r) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong totalHits = new AtomicLong();
        AtomicInteger failed = new AtomicInteger();
        try {
            for (int i = 0; i < numUsers; i++) {
                final int userId = i;
                pool.submit(() -> {
                    try {
                        searchOneUser(root, userId, kind, r, totalHits);
                    } catch (Throwable t) {
                        failed.incrementAndGet();
                        System.err.println("[search user=" + userId + "] " + t);
                    }
                });
            }
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow();
                throw new IllegalStateException("Search pool did not terminate");
            }
        }
        r.totalSearchHits = totalHits.get();
        if (failed.get() > 0) {
            System.err.println("WARN: search failures = " + failed.get());
        }
    }

    private static void searchOneUser(Path root, int userId, DirKind kind,
                                      Result r, AtomicLong totalHits) throws IOException {
        File path = root.resolve("user-" + userId).toFile();

        long t0 = System.nanoTime();
        Directory dir = openDirectory(kind, path);
        DirectoryReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);
        r.readerOpen.record(System.nanoTime() - t0);

        Random rng = new Random(0xBEEFL ^ userId);
        long t1 = System.nanoTime();
        long hitSum = 0;
        for (int q = 0; q < QUERIES_PER_USER; q++) {
            Query query = buildQuery(rng);
            TopDocs td = searcher.search(query, 10);
            hitSum += td.totalHits;
        }
        r.search.record(System.nanoTime() - t1);
        totalHits.addAndGet(hitSum);

        long t2 = System.nanoTime();
        reader.close();
        dir.close();
        r.readerClose.record(System.nanoTime() - t2);
    }

    private static Query buildQuery(Random rng) {
        // Mix simple TermQuery with small BooleanQuery to exercise scoring + I/O.
        if (rng.nextInt(3) == 0) {
            BooleanQuery bq = new BooleanQuery();
            bq.add(new TermQuery(new Term("content", LEXICON[rng.nextInt(LEXICON.length)])),
                   BooleanClause.Occur.SHOULD);
            bq.add(new TermQuery(new Term("content", LEXICON[rng.nextInt(LEXICON.length)])),
                   BooleanClause.Occur.SHOULD);
            return bq;
        }
        return new TermQuery(new Term("content", LEXICON[rng.nextInt(LEXICON.length)]));
    }

    private static String randomText(Random rng, int words) {
        StringBuilder sb = new StringBuilder(words * 8);
        for (int i = 0; i < words; i++) {
            if (i > 0) sb.append(' ');
            sb.append(LEXICON[rng.nextInt(LEXICON.length)]);
        }
        return sb.toString();
    }

    private static void printResult(Result r, boolean indexed, boolean searched) {
        System.out.println();
        System.out.println("---- " + r.name + " ----");
        if (indexed) {
            System.out.printf(Locale.ROOT,
                    " indexing wall=%.2f ms (concurrent across users)%n",
                    r.indexingWallNs / 1_000_000.0);
        }
        if (searched) {
            System.out.printf(Locale.ROOT,
                    " searching wall=%.2f ms  totalHits=%d%n",
                    r.searchingWallNs / 1_000_000.0, r.totalSearchHits);
        }
        printSection(r.writerOpen);
        printSection(r.indexing);
        printSection(r.writerClose);
        if (searched) {
            printSection(r.readerOpen);
            printSection(r.search);
            printSection(r.readerClose);
        }
        // Machine-parseable lines so parent processes (e.g. the matrix launcher)
        // can aggregate results across JDK installations.
        emitMachine(r, "writer.open", r.writerOpen);
        emitMachine(r, "indexing", r.indexing);
        emitMachine(r, "writer.close", r.writerClose);
        if (searched) {
            emitMachine(r, "reader.open", r.readerOpen);
            emitMachine(r, "search", r.search);
            emitMachine(r, "reader.close", r.readerClose);
        }
    }

    private static void emitMachine(Result r, String section, BenchmarkStats s) {
        BenchmarkStats.Summary sum = s.summarize();
        if (sum.count == 0) return;
        System.out.printf(Locale.ROOT,
                "BENCH-RESULT lucene=4.10.4 java=%s dir=%s section=%s n=%d avgMs=%.4f p95Ms=%.4f p99Ms=%.4f maxMs=%.4f totalMs=%.4f%n",
                System.getProperty("java.version"), r.name, section, sum.count,
                sum.avgMs(), sum.p95Ms(), sum.p99Ms(), sum.maxMs(), sum.totalMs());
    }

    private static void printSection(BenchmarkStats s) {
        BenchmarkStats.Summary sum = s.summarize();
        if (sum.count == 0) return;
        System.out.printf(Locale.ROOT,
                "  %-13s n=%-5d total=%9.2f ms  avg=%8.3f ms  min=%7.3f  p50=%7.3f  p95=%7.3f  p99=%7.3f  max=%7.3f%n",
                sum.label, sum.count,
                sum.totalMs(), sum.avgMs(),
                sum.minMs(), sum.p50Ms(), sum.p95Ms(), sum.p99Ms(), sum.maxMs());
    }

    private static void printComparison(List<Result> results) {
        if (results.size() < 2) return;
        Result a = results.get(0);
        Result b = results.get(1);
        System.out.println();
        System.out.println("================================================================");
        System.out.println(" Per-section winner (lower avg is better)");
        System.out.println("================================================================");
        compareSection("writer.open", a.writerOpen, b.writerOpen, a.name, b.name);
        compareSection("indexing", a.indexing, b.indexing, a.name, b.name);
        compareSection("writer.close", a.writerClose, b.writerClose, a.name, b.name);
        compareSection("reader.open", a.readerOpen, b.readerOpen, a.name, b.name);
        compareSection("search", a.search, b.search, a.name, b.name);
        compareSection("reader.close", a.readerClose, b.readerClose, a.name, b.name);
    }

    private static void compareSection(String label, BenchmarkStats sa, BenchmarkStats sb,
                                       String na, String nb) {
        BenchmarkStats.Summary a = sa.summarize();
        BenchmarkStats.Summary b = sb.summarize();
        if (a.count == 0 || b.count == 0) return;
        String winner;
        double diffPct;
        if (a.avgNs < b.avgNs) {
            winner = na;
            diffPct = (b.avgNs - a.avgNs) * 100.0 / b.avgNs;
        } else {
            winner = nb;
            diffPct = (a.avgNs - b.avgNs) * 100.0 / a.avgNs;
        }
        System.out.printf(Locale.ROOT,
                "  %-13s %s=%.3f ms vs %s=%.3f ms  -> %s wins by %.1f%%%n",
                label, na, a.avgMs(), nb, b.avgMs(), winner, diffPct);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        // Best effort; benchmarks live under a temp dir so leftovers are harmless.
        if (!f.delete()) f.deleteOnExit();
    }
}
