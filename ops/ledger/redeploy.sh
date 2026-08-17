#!/usr/bin/env bash
# Redeploy vela-ledger-service after a code change: reinstall the package into
# the existing venv and restart the systemd --user service. Assumes install.sh
# has already been run once (venv and unit already exist).
#
# Usage: ops/ledger/redeploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVICE_SRC="${REPO_ROOT}/services/ledger"
VENV_DIR="${HOME}/.local/share/vela-ledger-service/.venv"

if [[ ! -x "${VENV_DIR}/bin/pip" ]]; then
  echo "venv not found at ${VENV_DIR} — run ops/ledger/install.sh first" >&2
  exit 1
fi

echo "== reinstalling vela-ledger-service =="
"${VENV_DIR}/bin/pip" install -q --upgrade "${SERVICE_SRC}"

echo "== restarting service =="
systemctl --user restart vela-ledger-service.service

sleep 2
systemctl --user status vela-ledger-service.service --no-pager -l || true
