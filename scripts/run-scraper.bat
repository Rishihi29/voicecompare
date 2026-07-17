@echo off
REM ──────────────────────────────────────────────────────────────
REM  VoiceCompare — Run Scraper
REM  Usage: run-scraper.bat [LIVE|CACHED|LOCAL] [--demo]
REM
REM  LIVE    Fetch fresh data from live provider URLs (default)
REM  CACHED  Use on-disk HTML cache (max 24h old) — faster, offline-friendly
REM  LOCAL   Use pre-downloaded HTML in ./html_pages/
REM  --demo  Seed the search log with sample queries for demo purposes
REM ──────────────────────────────────────────────────────────────

if not exist out (
    echo [ERROR] Build first: scripts\build.bat
    exit /b 1
)

set MODE=%1
if "%MODE%"=="" set MODE=CACHED

echo [SCRAPER] Fetch mode: %MODE%
java -cp out VirtualPhoneScraperSuite %*

if %errorlevel% neq 0 (
    echo [ERROR] Scraper exited with errors. Check logs above.
    exit /b 1
)

echo [SCRAPER] Done. Data written to .\data\
echo [SCRAPER] Start the frontend: scripts\serve.bat
