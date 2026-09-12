#!/usr/bin/env bash
# Start the headless GhidraMCP host in the background and wait until it is serving.
#   ./start-headless-mcp.sh --write rs2client.949-4 --write rs2client.exe.949-4 [--read librs2client.so]
# Serves each writable target from 8080 upwards, then each read-only program on the ports after. The existing bridge
# (bridge_mcp_ghidra.py, scanning 8080-8089) + every mcp__ghidra__* tool then work with no GUI.
set -euo pipefail

GHIDRA="${GHIDRA_INSTALL_DIR:-/home/trent/projects/ghidra/build/dist/ghidra_12.1_DEV}"
export GHIDRA_INSTALL_DIR="$GHIDRA"
PROJECT_DIR="${PROJECT_DIR:-/home/trent/ghidra-proj}"
PROJECT_NAME="${PROJECT_NAME:-nxt-exe-2024-9-25}"
VENV="${PGVENV:-/tmp/pgvenv}"
BASE_PORT="${BASE_PORT:-8080}"
RUN_DIR="${GHIDRA_MCP_RUN_DIR:-/tmp/ghidra-mcp-headless}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIDFILE="$RUN_DIR/host.pid"
LOGFILE="$RUN_DIR/host.log"

WRITE_ARGS=(); READ_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --write) WRITE_ARGS+=(--write "$2"); shift 2;;
    --read)  READ_ARGS+=(--read "$2"); shift 2;;
    *) echo "unknown arg: $1"; exit 2;;
  esac
done
[ ${#WRITE_ARGS[@]} -gt 0 ] || { echo "usage: $0 --write rs2client.<new> [--write ...] [--read rs2client.<old> ...]"; exit 2; }

mkdir -p "$RUN_DIR"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "headless host already running (pid $(cat "$PIDFILE")); stop it first."; exit 1
fi

# pyghidra venv (same offline wheel as RS3ProtFinder)
if [ ! -x "$VENV/bin/python" ]; then
  echo "== creating pyghidra venv at $VENV =="
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --no-index --find-links "$GHIDRA/Ghidra/Features/PyGhidra/pypkg/dist" pyghidra
fi

# single-process project lock: refuse if a live process holds it; clean a stale lock
LOCK="$PROJECT_DIR/$PROJECT_NAME.lock"
if [ -f "$LOCK" ]; then
  if pgrep -f "ghidra.GhidraRun" >/dev/null 2>&1 || pgrep -f "analyzeHeadless" >/dev/null 2>&1 \
     || pgrep -f "mcp_headless_host" >/dev/null 2>&1; then
    echo "project lock held by a live process (GUI / analyzeHeadless / host); stop it first."; exit 1
  fi
  echo "removing stale project lock: $LOCK"
  rm -f "$LOCK" "$LOCK~" 2>/dev/null || true
fi

echo "== starting headless MCP host: ${WRITE_ARGS[*]} (writable from :$BASE_PORT) ${READ_ARGS[*]} =="
setsid nohup "$VENV/bin/python" "$HERE/mcp_headless_host.py" \
  --project-dir "$PROJECT_DIR" --project-name "$PROJECT_NAME" \
  "${WRITE_ARGS[@]}" "${READ_ARGS[@]}" --base-port "$BASE_PORT" \
  >"$LOGFILE" 2>&1 &
echo $! > "$PIDFILE"

# readiness gate: wait for /info on the writable port, or die with the log
for i in $(seq 1 60); do
  if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "host exited during startup; last log:"; tail -20 "$LOGFILE"; rm -f "$PIDFILE"; exit 1
  fi
  if curl -s "http://127.0.0.1:$BASE_PORT/info" 2>/dev/null | grep -q "status=ok"; then
    echo "== headless MCP ready =="
    for p in $(seq "$BASE_PORT" $((BASE_PORT + ${#WRITE_ARGS[@]}/2 + ${#READ_ARGS[@]}/2 - 1))); do
      name=$(curl -s "http://127.0.0.1:$p/info" 2>/dev/null | sed -n 's/^domain_file_name=//p')
      [ -n "$name" ] && echo "  :$p -> $name"
    done
    echo "(log: $LOGFILE  pid: $(cat "$PIDFILE"))"
    exit 0
  fi
  sleep 1
done
echo "timed out waiting for /info; last log:"; tail -20 "$LOGFILE"; exit 1
