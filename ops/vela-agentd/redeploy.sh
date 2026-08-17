#!/usr/bin/env bash
# One-command redeploy for vela-agentd.service.
#
# Re-copies the current unit file (in case it changed), reloads systemd,
# restarts the service, and runs the health check to confirm success.
#
# Usage: ops/vela-agentd/redeploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_SRC="${SCRIPT_DIR}/vela-agentd.service"
UNIT_DST="${HOME}/.config/systemd/user/vela-agentd.service"

echo "== redeploying vela-agentd.service =="

echo "-- syncing unit file --"
cp "${UNIT_SRC}" "${UNIT_DST}"

echo "-- reloading systemd user daemon --"
systemctl --user daemon-reload

echo "-- restarting service --"
systemctl --user restart vela-agentd.service

echo "-- waiting for startup --"
sleep 3

echo "-- verifying with health check --"
"${SCRIPT_DIR}/health-check.sh"

echo ""
echo "Redeploy complete."
