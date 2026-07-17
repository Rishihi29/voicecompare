@echo off
REM ──────────────────────────────────────────────────────────────
REM  VoiceCompare — Serve Frontend
REM  Starts a local static file server at http://localhost:3000
REM ──────────────────────────────────────────────────────────────
echo [SERVER] Starting VoiceCompare frontend...
echo [SERVER] Open http://localhost:3000 in your browser.
echo [SERVER] Press Ctrl+C to stop.
npx -y serve . --listen 3000
