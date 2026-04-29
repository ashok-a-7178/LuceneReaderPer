#!/usr/bin/env bash
# Plug-and-play runner for the LuceneReaderPer benchmark.
#
# Uses whichever `java` and `mvn` are first in your PATH. It does not look
# at any other JDK installations on the machine.
#
# Steps performed:
#   1. Print the active java / mvn versions so you can confirm the environment.
#   2. Build the three modules (skipped if all jars are already present and
#      no source file is newer than the jars).
#   3. Launch the interactive console runner.
#
# Pass arguments to forward them to the launcher (e.g. --auto):
#   ./run.sh                              # interactive
#   ./run.sh --auto 3 1000 BOTH BOTH 16   # scripted
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "================================================================"
echo " LuceneReaderPer — plug-and-play runner"
echo "================================================================"

# ---- 1. Show the environment we're going to use --------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: 'java' is not on PATH. Install JDK 11+ and try again." >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: 'mvn' is not on PATH. Install Maven 3.6+ and try again." >&2
  exit 1
fi
echo " java: $(command -v java)"
java -version
echo " mvn:  $(command -v mvn)"
mvn -v | head -1

# ---- 2. Build only when needed -------------------------------------------
LAUNCHER_JAR="launcher/target/launcher.jar"
LUCENE4_JAR="lucene4-bench/target/lucene4-bench.jar"
LUCENE9_JAR="lucene9-bench/target/lucene9-bench.jar"

needs_build=0
for jar in "$LAUNCHER_JAR" "$LUCENE4_JAR" "$LUCENE9_JAR"; do
  if [ ! -f "$jar" ]; then
    needs_build=1
    break
  fi
done
if [ $needs_build -eq 0 ]; then
  # Rebuild if any source file is newer than the oldest jar.
  newest_src="$(find launcher/src lucene4-bench/src lucene9-bench/src pom.xml \
      */pom.xml -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -1 || echo 0)"
  oldest_jar="$(stat -c '%Y' "$LAUNCHER_JAR" "$LUCENE4_JAR" "$LUCENE9_JAR" \
      2>/dev/null | sort -n | head -1 || echo 0)"
  if [ "${newest_src%.*}" -gt "${oldest_jar%.*}" ]; then
    needs_build=1
  fi
fi

if [ $needs_build -eq 1 ]; then
  echo
  echo " Building (mvn -q -DskipTests package)..."
  mvn -q -DskipTests package
else
  echo
  echo " Build artifacts are up to date — skipping build."
fi

# ---- 3. Launch the benchmark using the current JVM -----------------------
echo
echo " Launching benchmark..."
exec java -jar "$LAUNCHER_JAR" "$@"
