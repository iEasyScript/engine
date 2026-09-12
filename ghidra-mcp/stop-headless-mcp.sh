#!/usr/bin/env bash
# Stop the headless GhidraMCP host: final-save the writable program, release the project lock so
# the GUI can open it, and confirm the lock is gone.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/trent/ghidra-proj}"
PROJECT_NAME="${PROJECT_NAME:-nxt-exe-2024-9-25}"
BASE_PORT="${BASE_PORT:-8080}"
RUN_DIR="${GHIDRA_MCP_RUN_DIR:-/tmp/ghidra-mcp-headless}"
PIDFILE="$RUN_DIR/host.pid"

if [ ! -f "$PIDFILE" ]; then
  echo "no pidfile ($PIDFILE); host not tracked as running."
else
  PID="$(cat "$PIDFILE")"
  echo "== graceful shutdown via /shutdown (final save) =="
  curl -s -X POST "http://127.0.0.1:$BASE_PORT/shutdown" 2>/dev/null || true
  for i in $(seq 1 30); do
    kill -0 "$PID" 2>/dev/null || { echo "host exited."; break; }
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "still alive; SIGTERM $PID"; kill -TERM "$PID" 2>/dev/null || true
    for i in $(seq 1 15); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
  fi
  if kill -0 "$PID" 2>/dev/null; then
    echo "SIGKILL $PID (last resort — may skip final save)"; kill -KILL "$PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
fi

LOCK="$PROJECT_DIR/$PROJECT_NAME.lock"
if [ -f "$LOCK" ] && ! pgrep -f "mcp_headless_host" >/dev/null 2>&1; then
  echo "clearing residual project lock: $LOCK"; rm -f "$LOCK" "$LOCK~" 2>/dev/null || true
fi
echo "== stopped; project is free for the GUI =="
