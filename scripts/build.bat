@echo off
REM ──────────────────────────────────────────────────────────────
REM  VoiceCompare — Build Script
REM  Compiles the Java pipeline and test suite into ./out/
REM ──────────────────────────────────────────────────────────────
echo [BUILD] Compiling VirtualPhoneScraperSuite...
if not exist out mkdir out
javac -d out src\VirtualPhoneScraperSuite.java
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed. Ensure Java 17+ is installed.
    exit /b 1
)

echo [BUILD] Compiling test suite...
javac -d out -cp out src\VirtualPhoneScraperSuite.java tests\VoiceCompareSuiteTest.java
if %errorlevel% neq 0 (
    echo [ERROR] Test compilation failed.
    exit /b 1
)

echo [BUILD] Running tests...
java -cp out VoiceCompareSuiteTest
if %errorlevel% neq 0 (
    echo [ERROR] Tests failed.
    exit /b 1
)

echo [BUILD] Done. Run scripts\run-scraper.bat to fetch data.
