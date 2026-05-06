#!/usr/bin/env bash
    # Vela server smoke test — run before every deploy.
    # Usage: ./scripts/smoke-test.sh [HOST] [TOKEN]
    # Exits 0 on PASS, 1 on FAIL. Takes < 30 seconds.

    HOST="${1:-http://127.0.0.1:8410}"
    TOKEN="${2:-cjpOWhqUiF0hET9_Lj9qygN8P9JScIbU5EF3O3fFmIQ}"
    PASS=0; FAIL=0; SESSION_ID=""

    G="\033[32m✓\033[0m"; R="\033[31m✗\033[0m"
    pass() { printf "${G} %s\n" "$1"; PASS=$((PASS+1)); }
    fail() { printf "${R} %s\n" "$1"; [[ -n "${2:-}" ]] && printf "    got: %s\n" "$2"; FAIL=$((FAIL+1)); }
    info() { printf "  %s\n" "$*"; }

    echo ""; echo "=== Vela Server Smoke Test  $HOST ==="; echo ""

    # ── 1. Health ──────────────────────────────────────────────────────────────
    H=$(curl -sf --max-time 5 "$HOST/health" 2>/dev/null) || { fail "/health: no response"; H=""; }
    if [[ "$H" == *'"status":"healthy"'* ]]; then pass "/health → healthy"
    else fail "/health → not healthy" "$H"; fi

    SESSIONS=$(echo "$H" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('active_sessions',0))" 2>/dev/null || echo 0)
    info "active_sessions: $SESSIONS"
    APID=$(pgrep -f "amplifierd serve" | head -1)
  OPEN_FDS=$(lsof -p "$APID" 2>/dev/null | wc -l | tr -d " ")
  info "open_fds: $OPEN_FDS (limit: 65536)"
  if (( OPEN_FDS > 50000 )); then
      fail "open_fds > 50000 — check for session leak (fds=$OPEN_FDS)"; else
      pass "open_fds safe ($OPEN_FDS)"; fi

    # ── 2. Projects ────────────────────────────────────────────────────────────
    P=$(curl -sf --max-time 5 -H "x-amplifier-token: $TOKEN" "$HOST/projects" 2>/dev/null) || { fail "/projects: no response"; P="[]"; }
    N=$(echo "$P" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
    if [[ "$P" == *'"id"'* ]]; then pass "/projects → list ($N projects)"
    else fail "/projects → bad response" "$P"; fi
    if (( N > 0 )); then pass "/projects → non-empty"
    else fail "/projects → empty (no projects found)"; fi

    PROJ_ID=$(echo "$P" | python3 -c "import sys,json; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null || echo "")

    # ── 3. Project sessions ────────────────────────────────────────────────────
    if [[ -n "$PROJ_ID" ]]; then
      S=$(curl -sf --max-time 5 -H "x-amplifier-token: $TOKEN" "$HOST/projects/$PROJ_ID/sessions" 2>/dev/null)
      if [[ "$S" == *'"active"'* && "$S" == *'"recent"'* ]]; then pass "/projects/:id/sessions → active+recent"
      else fail "/projects/:id/sessions → bad response" "$S"; fi
    fi

    # ── 4. Session create ──────────────────────────────────────────────────────
    SC=$(curl -sf --max-time 10 -H "x-amplifier-token: $TOKEN" -H "Content-Type: application/json" \
      -d '{"bundle_name":"vela"}' "$HOST/sessions" 2>/dev/null)
    SESSION_ID=$(echo "$SC" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('session_id',''))" 2>/dev/null || echo "")
    if [[ -n "$SESSION_ID" ]]; then pass "POST /sessions → created (${SESSION_ID:0:8}...)"
    else fail "POST /sessions → failed" "$SC"; fi

    # ── 5-8. Session lifecycle ─────────────────────────────────────────────────
    if [[ -n "$SESSION_ID" ]]; then
      # Status
      ST=$(curl -sf --max-time 5 -H "x-amplifier-token: $TOKEN" "$HOST/sessions/$SESSION_ID" 2>/dev/null)
      if [[ "$ST" == *'"status"'* ]]; then pass "GET /sessions/:id → status present"
      else fail "GET /sessions/:id → bad" "$ST"; fi

      # Execute
      EX=$(curl -sf --max-time 10 -H "x-amplifier-token: $TOKEN" -H "Content-Type: application/json" \
        -d '{"prompt":"reply with exactly: SMOKE_OK"}' "$HOST/sessions/$SESSION_ID/execute/stream" 2>/dev/null)
      CORR=$(echo "$EX" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('correlation_id',''))" 2>/dev/null || echo "")
      if [[ "$EX" == *'"status":"accepted"'* && -n "$CORR" ]]; then pass "POST /execute/stream → accepted (corr=${CORR:0:12}...)"
      else fail "POST /execute/stream → bad" "$EX"; fi

      # SSE — wait for orchestrator:complete
      SSE=$(curl -sf --max-time 20 -H "x-amplifier-token: $TOKEN" -H "Accept: text/event-stream" \
        "$HOST/events?session=$SESSION_ID" 2>/dev/null | head -c 8192 || echo "")
      if [[ "$SSE" == *"event:"* ]]; then pass "GET /events?session → SSE opens"
      else fail "GET /events?session → no SSE data" "${SSE:0:100}"; fi
      if [[ "$SSE" == *"orchestrator:complete"* ]]; then pass "GET /events?session → orchestrator:complete"
      else fail "GET /events?session → no completion in 20s" "${SSE: -200}"; fi

      # Transcript
      sleep 0.5
      TR=$(curl -sf --max-time 5 -H "x-amplifier-token: $TOKEN" "$HOST/sessions/$SESSION_ID/transcript" 2>/dev/null)
      if [[ "$TR" == *'"messages"'* ]]; then pass "GET /sessions/:id/transcript → messages"
      else fail "GET /sessions/:id/transcript → bad" "${TR:0:200}"; fi
      if [[ "$TR" == *"SMOKE_OK"* ]]; then pass "transcript → LLM replied with SMOKE_OK"
      else fail "transcript → SMOKE_OK not found (LLM may have paraphrased)" "${TR: -300}"; fi

      # Steer
      STEER_CODE=$(curl -sf -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "x-amplifier-token: $TOKEN" -H "Content-Type: application/json" \
        -d '{"message":"test"}' "$HOST/sessions/$SESSION_ID/steer" 2>/dev/null || echo "000")
      if [[ "$STEER_CODE" == "200" || "$STEER_CODE" == "404" ]]; then pass "POST /sessions/:id/steer → $STEER_CODE (ok)"
      else fail "POST /sessions/:id/steer → HTTP $STEER_CODE (expected 200 or 404)"; fi
    fi

    # ── Summary ────────────────────────────────────────────────────────────────
    TOTAL=$((PASS+FAIL))
    echo ""; echo "─────────────────────────────────"
    if (( FAIL == 0 )); then
      printf "\033[32mPASS\033[0m  %d/%d\n" "$PASS" "$TOTAL"
      echo "Server ready — safe to deploy."
      exit 0
    else
      printf "\033[31mFAIL\033[0m  %d/%d  (%d failed)\n" "$PASS" "$TOTAL" "$FAIL"
      echo "Fix server issues before deploying."
      exit 1
    fi
    