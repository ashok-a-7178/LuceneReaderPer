# Running the benchmark — plug & play

This guide gets you from a fresh clone to results in three commands. The
benchmark uses **whichever JDK is currently active** (`java -version`) — it
does **not** scan your machine for other JDK installations. If you want to
test a different JDK, simply switch the active `java` (e.g. `JAVA_HOME=…`) and
re-run.

---

## 1. Prerequisites

| Requirement | Minimum version | Check with |
|-------------|-----------------|-----------|
| JDK         | 11+ (17/21/25 also fine) | `java -version` |
| Maven       | 3.6+              | `mvn -v` |
| Disk        | ~200 MB            | `df -h .` |
| OS          | Linux, macOS, or Windows | — |

> **Tip:** the benchmark works on whatever `java` is first in your `PATH`. To
> use a specific JDK, just put it first:
> ```bash
> export JAVA_HOME=/path/to/your/jdk
> export PATH="$JAVA_HOME/bin:$PATH"
> java -version   # confirm
> ```

---

## 2. Run it

### Linux / macOS

```bash
git clone https://github.com/ashok-a-7178/LuceneReaderPer.git
cd LuceneReaderPer
./run.sh
```

### Windows

```bat
git clone https://github.com/ashok-a-7178/LuceneReaderPer.git
cd LuceneReaderPer
run.bat
```

That's it. The script will:

1. Print the active `java` and `mvn` versions so you can verify your JVM.
2. Build the three modules (only when needed — skipped on subsequent runs).
3. Launch the **interactive console runner**.

---

## 3. Answer the prompts

The runner asks five questions:

```
Choose Lucene version:    1) 4.10.4   2) 9.10.0   3) Both
Number of users:          e.g. 100, 1000, 2000
Directory:                1) NIOFS    2) MMAP     3) Both
Workload:                 1) Indexing only    2) Indexing + Searching
Concurrency (threads):    default = 2 × CPU cores
```

For the standard "which directory wins?" comparison choose **3 / 1000 / 3 / 2 /
\<enter\>**. The benchmark will:

- Spawn a fresh JVM per Lucene version (Lucene 4 and 9 cannot share a
  classpath).
- Apply Lucene-tuned GC/JVM flags (G1, `AlwaysPreTouch`, `--add-opens` for
  `MMapDirectory`, etc. — see the README's "Lucene-tuned JVM / GC options"
  table).
- Index and search `N` independent indexes concurrently using the chosen
  directory implementation(s).
- Print per-section timings (`writer.open`, `indexing`, `writer.close`,
  `reader.open`, `search`, `reader.close`) for each directory and end with a
  **per-section winner** table:

```
================================================================
 Per-section winner (lower avg is better)
================================================================
  writer.open   NIOFSDirectory=11.514 ms vs MMapDirectory=1.024 ms  -> MMapDirectory wins by 91.1%
  indexing      NIOFSDirectory=31.612 ms vs MMapDirectory=19.147 ms -> MMapDirectory wins by 39.4%
  search        NIOFSDirectory=7.664  ms vs MMapDirectory=3.923 ms  -> MMapDirectory wins by 48.8%
```

---

## 4. Scripted / non-interactive

To run without prompts, pass `--auto` through the wrapper:

```bash
# version: 1=Lucene 4, 2=Lucene 9, 3=Both
./run.sh --auto <version> <users> <NIOFS|MMAP|BOTH> <INDEX|BOTH> [threads]

# Example: 1000 users, both Lucene versions, both directories,
# index + search, 16 threads
./run.sh --auto 3 1000 BOTH BOTH 16
```

Same arguments work for `run.bat` on Windows.

---

## 5. Re-running on a different JDK

No special command needed — switch your active JDK and re-run:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./run.sh --auto 3 1000 BOTH BOTH 16
```

The runner picks up the new JVM automatically and reports its version in the
header, e.g. `BENCH-RESULT … java=21.0.5 …`, so you can tell results apart.

---

## 6. Troubleshooting

| Symptom | Fix |
|---------|-----|
| `'java' is not on PATH` | Install JDK 11+ and ensure `java -version` works. |
| `'mvn' is not on PATH` | Install Maven 3.6+. |
| Build fails with `Could not transfer artifact … from central` | Network/proxy issue. Set `https_proxy` or use an internal mirror in `~/.m2/settings.xml`. |
| Benchmark fails on Java 8 / older | Lucene 9.10.0 requires Java 11+. Use a newer JDK. |
| Numbers look noisy | Increase the user count (e.g. 2000+) — the run is too short to reach steady state. |

---

## 7. Cleaning up

```bash
mvn clean    # removes target/ in every module
```

The benchmark uses an OS temp directory for indexes and deletes it at the end
of every run, so no extra cleanup is required.
