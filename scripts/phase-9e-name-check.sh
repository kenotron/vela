#!/usr/bin/env bash
# scripts/phase-9e-name-check.sh
# Phase 9E pre-publish verification: confirms all 17 target crate names are
# available on crates.io (not owned by anyone else).
#
# Run from either the vela or amplifier-rust repo root.
# Requires: cargo (with crates.io access — no auth needed for cargo search)
#
# Usage:
#   bash scripts/phase-9e-name-check.sh
#
# Expected: prints "PASS: All 17 crate names are available on crates.io."
# If any name is taken by someone else, prints CONFLICT and exits 1.

set -euo pipefail

CRATES=(
    amplifier-module-context-simple
    amplifier-module-provider-anthropic
    amplifier-module-provider-openai
    amplifier-module-provider-gemini
    amplifier-module-provider-ollama
    amplifier-module-tool-bash
    amplifier-module-tool-filesystem
    amplifier-module-tool-search
    amplifier-module-tool-todo
    amplifier-module-tool-web
    amplifier-module-tool-task
    amplifier-module-agent-runtime
    amplifier-module-session-store
    amplifier-module-tool-skills
    amplifier-module-orchestrator-loop-streaming
    amplifier-module-tool-delegate
    amplifier-agent-foundation
)

CONFLICTS=0
AVAILABLE=0

echo "Phase 9E: Checking crates.io name availability for ${#CRATES[@]} target crates..."
echo "Date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo

for crate in "${CRATES[@]}"; do
    result=$(cargo search "${crate}" --limit 1 2>&1 || true)

    # Only flag an exact name match as a conflict (not partial substring matches)
    exact_match=$(echo "${result}" | grep -E "^${crate}[[:space:]]*=" || true)

    if [[ -z "${exact_match}" ]]; then
        echo "  AVAILABLE: ${crate}"
        AVAILABLE=$((AVAILABLE + 1))
    else
        echo "  CONFLICT:  ${crate}"
        echo "             ${exact_match}"
        echo "             Verify ownership: https://crates.io/crates/${crate}/owners"
        CONFLICTS=$((CONFLICTS + 1))
    fi
done

echo
echo "Results: ${AVAILABLE} available, ${CONFLICTS} conflicts (out of ${#CRATES[@]} total)"

if [[ ${CONFLICTS} -gt 0 ]]; then
    echo
    echo "FAIL: ${CONFLICTS} crate name(s) are already taken on crates.io."
    echo "Rename each conflicting crate before proceeding with Phase 9E publish."
    exit 1
fi

echo
echo "PASS: All ${#CRATES[@]} crate names are available on crates.io."
echo "No conflicts detected. Safe to proceed with Phase 9E publish workflow."
