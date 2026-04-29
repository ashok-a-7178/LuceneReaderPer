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
# If all three jars exist we skip the build for a fast restart. Run
# `mvn clean` to force a rebuild after editing sources.
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

if [ $needs_build -eq 1 ]; then
  echo
  echo " Building (mvn -q -DskipTests package)..."
  mvn -q -DskipTests package
else
  echo
  echo " Build artifacts are present — skipping build."
  echo " (run 'mvn clean' to force a rebuild after editing sources)"
fi

# ---- 3. Launch the benchmark using the current JVM -----------------------
echo
echo " Launching benchmark..."
exec java -jar "$LAUNCHER_JAR" "$@"
