#!/usr/bin/env bash
# Health check for vela-agent-serve.service.
#
# Usage: ops/agent-serve/health-check.sh [--port PORT] [--key API_KEY]
#
# Exit codes:
#   0 - healthy: service active, unauthenticated request rejected (401),
#       authenticated request to /v1/models succeeds (200)
#   1 - systemd unit is not active
#   2 - port not reachable / connection failed
#   3 - unauthenticated request was NOT rejected (auth misconfigured)
#   4 - authenticated request failed
set -euo pipefail

PORT=9099
API_KEY="${AMPLIFIER_AGENT_HTTP_API_KEY:-}"
ENV_FILE="${HOME}/.amplifier/vela-agent-serve/env"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --key) API_KEY="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done

if [[ -z "$API_KEY" && -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  API_KEY="${AMPLIFIER_AGENT_HTTP_API_KEY:-}"
fi

echo "== systemd unit status =="
if ! systemctl --user is-active --quiet vela-agent-serve.service; then
  echo "FAIL: vela-agent-serve.service is not active"
  systemctl --user status vela-agent-serve.service --no-pager -l || true
  exit 1
fi
echo "OK: service is active"

BASE_URL="http://127.0.0.1:${PORT}"

echo "== unauthenticated request (expect 401) =="
UNAUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${BASE_URL}/v1/models" || echo "000")
if [[ "$UNAUTH_CODE" == "000" ]]; then
  echo "FAIL: could not reach ${BASE_URL} (connection failed)"
  exit 2
fi
if [[ "$UNAUTH_CODE" != "401" ]]; then
  echo "FAIL: unauthenticated request returned ${UNAUTH_CODE}, expected 401 — auth may not be enforced"
  exit 3
fi
echo "OK: unauthenticated request correctly rejected (401)"

echo "== authenticated request (expect 200) =="
if [[ -z "$API_KEY" ]]; then
  echo "FAIL: no API key available to test authenticated path (set AMPLIFIER_AGENT_HTTP_API_KEY or --key)"
  exit 4
fi
AUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
  -H "Authorization: Bearer ${API_KEY}" "${BASE_URL}/v1/models" || echo "000")
if [[ "$AUTH_CODE" != "200" ]]; then
  echo "FAIL: authenticated request returned ${AUTH_CODE}, expected 200"
  exit 4
fi
echo "OK: authenticated request succeeded (200)"

echo "== HEALTHY =="
exit 0
