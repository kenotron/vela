#!/usr/bin/env bash
# One-time installer for vela-agentd.service.
#
# Installs the systemd --user unit, creates the env file (if absent),
# and enables + starts the service. Idempotent -- safe to re-run.
#
# Same conventions as ops/agent-serve/install.sh: only ExecStart / the
# service name differ for the vela-agentd fork.
#
# Usage: ops/vela-agentd/install.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_SRC="${SCRIPT_DIR}/vela-agentd.service"
UNIT_DST="${HOME}/.config/systemd/user/vela-agentd.service"
ENV_DIR="${HOME}/.amplifier/vela-agentd"
ENV_FILE="${ENV_DIR}/env"

echo "== installing systemd --user unit =="
mkdir -p "${HOME}/.config/systemd/user"
cp "${UNIT_SRC}" "${UNIT_DST}"
echo "installed: ${UNIT_DST}"

echo "== ensuring env file exists =="
mkdir -p "${ENV_DIR}"
if [[ ! -f "${ENV_FILE}" ]]; then
  API_KEY="$(openssl rand -hex 32)"
  cat > "${ENV_FILE}" <<EOF
# Environment for vela-agentd.service -- DO NOT COMMIT (lives outside repo).
AMPLIFIER_AGENT_HTTP_API_KEY=${API_KEY}
AMPLIFIER_AGENT_HTTP_WORKSPACE=vela-lane-3-1
AMPLIFIER_AGENT_HTTP_MODEL_ID=amplifier

# F2: approval gate timeout (seconds). Default 30s if unset.
VELA_AGENTD_APPROVAL_TIMEOUT_SECONDS=30

# F4: ledger service base URL this instance proxies /ledger/* to.
VELA_AGENTD_LEDGER_BASE_URL=http://localhost:9199

# Provider credentials (required -- the wire face needs at least one resolvable
# provider). Set the key(s) your deployment uses:
# ANTHROPIC_API_KEY=
# OPENAI_API_KEY=
EOF
  chmod 600 "${ENV_FILE}"
  echo "created: ${ENV_FILE} (generated a fresh API key -- edit to add provider credentials)"
else
  echo "exists: ${ENV_FILE} (leaving in place)"
fi

if ! grep -qE '^(ANTHROPIC_API_KEY|OPENAI_API_KEY|AZURE_OPENAI_API_KEY)=' "${ENV_FILE}" 2>/dev/null; then
  echo ""
  echo "WARNING: ${ENV_FILE} has no provider credential set."
  echo "  The service will fail to start until one is added, e.g.:"
  echo "    echo 'ANTHROPIC_API_KEY=sk-ant-...' >> ${ENV_FILE}"
  echo ""
fi

echo "== reloading systemd user daemon =="
systemctl --user daemon-reload

echo "== enabling + starting service =="
systemctl --user enable --now vela-agentd.service

sleep 2
echo "== status =="
systemctl --user status vela-agentd.service --no-pager -l || true

echo ""
echo "Run ops/vela-agentd/health-check.sh to verify."
