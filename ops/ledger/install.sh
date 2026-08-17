#!/usr/bin/env bash
# One-time installer for vela-ledger-service.service.
#
# Creates a dedicated venv under ~/.local/share/vela-ledger-service, installs the
# service package into it, installs the systemd --user unit, and enables + starts
# the service. Idempotent — safe to re-run (re-run to pick up code changes too;
# see redeploy.sh for the lighter-weight update-only path).
#
# Usage: ops/ledger/install.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVICE_SRC="${REPO_ROOT}/services/ledger"

UNIT_SRC="${SCRIPT_DIR}/vela-ledger-service.service"
UNIT_DST="${HOME}/.config/systemd/user/vela-ledger-service.service"
ENV_DIR="${HOME}/.amplifier/vela-ledger-service"
ENV_FILE="${ENV_DIR}/env"
VENV_DIR="${HOME}/.local/share/vela-ledger-service/.venv"

echo "== creating/updating venv at ${VENV_DIR} =="
mkdir -p "$(dirname "${VENV_DIR}")"
python3 -m venv "${VENV_DIR}"
"${VENV_DIR}/bin/pip" install -q --upgrade pip
"${VENV_DIR}/bin/pip" install -q "${SERVICE_SRC}"
echo "installed vela-ledger-service into ${VENV_DIR}"

echo "== installing systemd --user unit =="
mkdir -p "${HOME}/.config/systemd/user"
cp "${UNIT_SRC}" "${UNIT_DST}"
echo "installed: ${UNIT_DST}"

echo "== ensuring env file exists =="
mkdir -p "${ENV_DIR}"
if [[ ! -f "${ENV_FILE}" ]]; then
  cat > "${ENV_FILE}" <<EOF
# Environment for vela-ledger-service.service — DO NOT COMMIT (lives outside repo).
# No secrets required by default; this file exists for future auth/config knobs.
EOF
  chmod 600 "${ENV_FILE}"
  echo "created: ${ENV_FILE}"
else
  echo "exists: ${ENV_FILE} (leaving in place)"
fi

echo "== reloading systemd user daemon =="
systemctl --user daemon-reload

echo "== enabling + starting service =="
systemctl --user enable --now vela-ledger-service.service

sleep 2
echo "== status =="
systemctl --user status vela-ledger-service.service --no-pager -l || true

echo ""
echo "Run ops/ledger/health-check.sh to verify."
