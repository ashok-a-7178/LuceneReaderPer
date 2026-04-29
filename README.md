# LuceneReaderPer

Concurrent benchmark comparing `NIOFSDirectory` and `MMapDirectory` under highly
concurrent indexing and searching, runnable on **Lucene 4.10.4**, **Lucene
9.10.0**, or **both** — selected interactively from the console.

## Why a multi-module project?

Lucene 4 and Lucene 9 share the `org.apache.lucene` package namespace and have
incompatible APIs (`IndexWriterConfig` ctor, `Directory` ctor takes `File` vs
`Path`, `BooleanQuery` builder, `TotalHits`, …). They cannot coexist on a single
classpath. The project therefore consists of three modules with isolated
classpaths:

| Module          | Purpose                                                                 |
|-----------------|-------------------------------------------------------------------------|
| `lucene4-bench` | Benchmark code compiled & shaded against Lucene 4.10.4                  |
| `lucene9-bench` | Same benchmark, against Lucene 9.10.0                                   |
| `launcher`      | Interactive console runner that prompts the user and spawns the right shaded jar(s) in fresh JVMs |

The two benchmark modules implement the **same workload** (same documents,
queries, concurrency model, sections measured) so the numbers they emit are
directly comparable.

## What is measured

For a chosen number `N` of unique users, the benchmark creates one Lucene
index per user and performs:

1. **Concurrent indexing** of `N` user indexes via an `ExecutorService`. For
   each user it times:
   - `writer.open`  — `Directory` open + `IndexWriter` construction
   - `indexing`     — adding a fixed sample of documents + commit
   - `writer.close` — `IndexWriter` close + `Directory` close
2. **Concurrent searching** of the same `N` indexes via an `ExecutorService`.
   For each user it times:
   - `reader.open`  — `Directory` open + `DirectoryReader.open` + `IndexSearcher` construction
   - `search`       — running a fixed mix of `TermQuery` / `BooleanQuery` queries
   - `reader.close` — `DirectoryReader` close + `Directory` close

Each section is aggregated into `count / total / avg / min / p50 / p95 / p99 /
max` (all ms). At the end a **per-section winner** table prints which
`Directory` was faster on average and by what percentage — that is the
"benchmark must tightly focus on each section and provide which is better"
deliverable.

## Build

Requires Maven 3.6+ and JDK 11+ (Lucene 9 requires Java 11; Lucene 4 still
runs fine on 11/17).

```bash
mvn -q -DskipTests package
```

This produces three artifacts:

- `lucene4-bench/target/lucene4-bench.jar` (shaded, runnable)
- `lucene9-bench/target/lucene9-bench.jar` (shaded, runnable)
- `launcher/target/launcher.jar`           (interactive runner)

## Run

### Interactive (recommended)

```bash
java -jar launcher/target/launcher.jar
```

The launcher prompts for:

```
Choose Lucene version:    1) 4.10.4   2) 9.10.0   3) Both
Number of users:          e.g. 100, 1000, 2000
Directory:                1) NIOFS   2) MMAP   3) Both
Workload:                 1) Indexing only   2) Indexing + Searching
Concurrency (threads):    default = 2 × CPU cores
```

It then spawns the appropriate shaded jar(s) in fresh JVMs, streaming output
live to your console.

### Non-interactive / scripted

```bash
# version: 1=Lucene4, 2=Lucene9, 3=Both
java -jar launcher/target/launcher.jar --auto <version> <users> <NIOFS|MMAP|BOTH> <INDEX|BOTH> [threads]

# example: 1000 users, both Lucene versions, both directories, indexing + searching
java -jar launcher/target/launcher.jar --auto 3 1000 BOTH BOTH 16
```

### Direct (single Lucene version)

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar lucene9-bench/target/lucene9-bench.jar 2000 BOTH BOTH 32
```

The `--add-opens` flags silence Lucene 9's reflective-access warnings for
`MMapDirectory`'s buffer-unmap helper on Java 11–17.

## Sample output (excerpt)

```
---- MMapDirectory ----
 indexing wall=173.77 ms (concurrent across users)
 searching wall=65.32 ms  totalHits=21398
  writer.open   n=25    total=  43.91 ms  avg=  1.756 ms  ...
  indexing      n=25    total= 596.66 ms  avg= 23.866 ms  ...
  search        n=25    total= 130.92 ms  avg=  5.237 ms  ...

================================================================
 Per-section winner (lower avg is better)
================================================================
  writer.open   NIOFS=5.668 ms vs MMAP=1.756 ms   -> MMapDirectory wins by 69.0%
  indexing      NIOFS=50.639 ms vs MMAP=23.866 ms -> MMapDirectory wins by 52.9%
  search        NIOFS=16.604 ms vs MMAP=5.237 ms  -> MMapDirectory wins by 68.5%
```

## NIOFSDirectory vs MMapDirectory — what to expect

- **`NIOFSDirectory`** uses `FileChannel.read(ByteBuffer, position)` — every
  read crosses the JNI boundary and incurs a syscall. It works on every OS and
  every JVM but pays per-read overhead. It is the safe default when address
  space is constrained (32-bit JVMs) or when files are small or accessed
  sparsely.
- **`MMapDirectory`** maps index files into the JVM's virtual address space.
  Reads then become normal memory accesses serviced by the OS page cache,
  with no syscalls on hot paths. On 64-bit OSes with enough virtual address
  space (the Lucene recommendation since 4.x) it generally wins on
  search-heavy and random-read workloads — which is exactly what this
  benchmark demonstrates.

The project lets you confirm the trade-off **on your own hardware, OS, and
JVM**, for **your chosen concurrency level and user count**, on **the Lucene
version you actually deploy**.
