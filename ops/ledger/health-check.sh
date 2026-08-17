#!/usr/bin/env bash
# Quick liveness + smoke check for vela-ledger-service.
#
# Usage: ops/ledger/health-check.sh [port]
set -euo pipefail

PORT="${1:-9199}"
BASE="http://localhost:${PORT}"

echo "== systemd status =="
systemctl --user is-active vela-ledger-service.service || true

echo "== /healthz =="
curl -sf "${BASE}/healthz" && echo "" || {
  echo "FAILED: /healthz unreachable on port ${PORT}" >&2
  exit 1
}

echo "== smoke: create + fetch a job =="
JOB_ID=$(curl -sf -X POST "${BASE}/ledger/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"origin":{"session_id":"health-check","turn_id":"t0","tool_call_id":"health-check-'"$(date +%s)"'"},"spec":{"kind":"health-check"}}' \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["job_id"])')
echo "created job: ${JOB_ID}"

curl -sf "${BASE}/ledger/jobs/${JOB_ID}" > /dev/null && echo "fetched job OK"

echo "health check passed."
