#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

if ! command -v ssh >/dev/null 2>&1; then
  echo "Missing required command: ssh" >&2
  exit 1
fi

if [[ ! -x "./gradlew" ]]; then
  echo "Cannot find executable ./gradlew in $ROOT_DIR" >&2
  exit 1
fi

APP_LOG="${TMPDIR:-/tmp}/flowlite-runTestApp.log"

echo "Starting runTestApp..."
./gradlew runTestApp >"$APP_LOG" 2>&1 &
APP_PID=$!

echo "runTestApp pid: $APP_PID"
echo "App log: $APP_LOG"

cleanup() {
  if kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "Waiting for http://127.0.0.1:8080/api/flows ..."
for _ in {1..120}; do
  if curl -fsS "http://127.0.0.1:8080/api/flows" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:8080/api/flows" >/dev/null 2>&1; then
  echo "runTestApp did not become ready in time. Check: $APP_LOG" >&2
  exit 1
fi

echo "App is ready. Opening public tunnel via localhost.run ..."
echo "Press Ctrl+C to close tunnel and stop runTestApp."

ssh -o StrictHostKeyChecking=no -o ExitOnForwardFailure=yes -R 80:localhost:8080 nokey@localhost.run
