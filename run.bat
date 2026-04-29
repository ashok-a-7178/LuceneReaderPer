@echo off
REM Plug-and-play runner for the LuceneReaderPer benchmark on Windows.
REM Uses whichever java and mvn are first on PATH.

setlocal
cd /d "%~dp0"

echo ================================================================
echo  LuceneReaderPer -- plug-and-play runner
echo ================================================================

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: 'java' is not on PATH. Install JDK 11+ and try again.
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: 'mvn' is not on PATH. Install Maven 3.6+ and try again.
  exit /b 1
)

java -version
mvn -v | findstr /B "Apache Maven"

set "LAUNCHER_JAR=launcher\target\launcher.jar"
set "LUCENE4_JAR=lucene4-bench\target\lucene4-bench.jar"
set "LUCENE9_JAR=lucene9-bench\target\lucene9-bench.jar"

if not exist "%LAUNCHER_JAR%" goto build
if not exist "%LUCENE4_JAR%" goto build
if not exist "%LUCENE9_JAR%" goto build
echo  Build artifacts are present -- skipping build.
goto run

:build
echo.
echo  Building (mvn -q -DskipTests package)...
call mvn -q -DskipTests package || exit /b 1

:run
echo.
echo  Launching benchmark...
java -jar "%LAUNCHER_JAR%" %*
endlocal
