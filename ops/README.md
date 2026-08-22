# Ops — agent-serve (Lane 1.4)

Runs a **stock, unmodified** `amplifier-agent serve chat-completions` instance under
process supervision, documents how to reach it, enforces auth, and provides a
one-command redeploy.

This directory does not fork or modify `amplifier-agent`. It only wraps the stock
CLI with a systemd unit and a few scripts. When Stage 3 (lane 3.1) swaps in
`vela-agentd`, only `ExecStart` in the unit file needs to change — everything
else (supervision, auth, health check, redeploy) stays the same.

## Layout

```
ops/
  README.md                       — this file
  agent-serve/
    vela-agent-serve.service      — systemd --user unit (source of truth, versioned)
    install.sh                    — one-time setup: install unit, create env file, enable+start
    redeploy.sh                   — one-command redeploy: sync unit, restart, verify
    health-check.sh               — verify service is up, auth enforced, endpoint reachable
```

Runtime state lives **outside the repo**, at `~/.amplifier/vela-agent-serve/env` —
it holds the shared-secret API key and provider credentials, and must never be
committed.

## Prerequisites

- `amplifier-agent` installed and on `PATH` (`which amplifier-agent`).
- A resolvable LLM provider credential (e.g. `ANTHROPIC_API_KEY` in the env
  file, or already persisted via `amplifier-agent auth set anthropic <key>`).
- `systemd --user` available and running (standard on any modern Linux desktop
  or server; confirmed working on this host).
- `curl` for health checks.

## First-time install

```bash
ops/agent-serve/install.sh
```

This:
1. Copies `vela-agent-serve.service` to `~/.config/systemd/user/`.
2. Creates `~/.amplifier/vela-agent-serve/env` with a freshly generated API key
   if one doesn't already exist (never overwrites an existing env file).
3. Warns if no provider credential is present in the env file — the service
   will fail to start (exit code 3, "no providers configured") until you add
   one, e.g.:
   ```bash
   echo 'ANTHROPIC_API_KEY=sk-ant-...' >> ~/.amplifier/vela-agent-serve/env
   ```
4. Runs `systemctl --user daemon-reload && systemctl --user enable --now vela-agent-serve.service`.

Then verify:

```bash
ops/agent-serve/health-check.sh
```

## Redeploy (one command)

After editing the unit file or after a code/config change, run:

```bash
ops/agent-serve/redeploy.sh
```

This re-syncs the unit file, reloads systemd, restarts the service, waits for
startup, and runs the health check — failing loudly (non-zero exit) if
anything is wrong. **Exercised and verified working** as part of this lane's
completion (see verification section below).

## Supervision model

- **Supervisor:** `systemd --user` (no root required). The unit is installed
  under `~/.config/systemd/user/vela-agent-serve.service` and enabled against
  `default.target`, so it starts whenever the user session starts (matches the
  pattern already used by other services on this host, e.g.
  `amplifier-resolve.service`).
- **Restart policy:** `Restart=on-failure`, `RestartSec=5s` — a crashed process
  is automatically restarted within 5 seconds.
- **Simulated reboot verification:** killed the running process with `kill -9`
  and confirmed systemd restarted it automatically (`systemctl --user
  is-active` returned `active` again within the RestartSec window, new PID
  confirmed via `systemctl --user status`). A literal host reboot was not
  available in this sandboxed environment, so this is the closest available
  simulation — restarting the systemd **user** session itself is the
  equivalent of "reboot" for a `systemd --user` unit, since user units don't
  survive an actual machine reboot unless lingering is enabled
  (`loginctl enable-linger <user>`, requires privileges not available in this
  environment — see Residuals below).

## Auth

The server enforces a shared-secret bearer token on every request
(`Authorization: Bearer <key>`), sourced from `AMPLIFIER_AGENT_HTTP_API_KEY` in
the env file. This is the stock `amplifier-agent` auth mechanism — no custom
auth code was added.

Verified:

```bash
# unauthenticated -> 401
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9099/v1/models
# => 401

# authenticated -> 200
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $(grep AMPLIFIER_AGENT_HTTP_API_KEY ~/.amplifier/vela-agent-serve/env | cut -d= -f2)" \
  http://127.0.0.1:9099/v1/models
# => 200
```

`ops/agent-serve/health-check.sh` runs both checks automatically and fails
(non-zero exit) if either doesn't behave as expected.

**Caveat:** the stock server binds `--bind 0.0.0.0` in this unit so it's
reachable off-localhost (required for phone/remote access). The CLI's own
`--help` text flags this tradeoff explicitly: "Only set to 0.0.0.0 if you
understand the auth/exposure tradeoff — the POC only ships a shared-secret
bearer check." The bearer check is the only auth layer; there is no mTLS or
per-user auth. Treat the shared secret as sensitive and rotate it if exposed.

## Health check & log tailing

**Health check:**

```bash
ops/agent-serve/health-check.sh
```

Checks (in order): systemd unit is `active`, unauthenticated request is
rejected (401), authenticated request to `/v1/models` succeeds (200). Exits
non-zero with a labeled reason on any failure (see script header for exit code
meanings).

**Log tailing:**

```bash
# follow live logs
journalctl --user -u vela-agent-serve.service -f

# last 100 lines
journalctl --user -u vela-agent-serve.service -n 100 --no-pager

# logs since a given time
journalctl --user -u vela-agent-serve.service --since "10 min ago"
```

**Service control:**

```bash
systemctl --user status vela-agent-serve.service
systemctl --user restart vela-agent-serve.service
systemctl --user stop vela-agent-serve.service
```

(`amplifier-agent serve stop` / `serve restart` / `serve status` also exist as
CLI-native alternatives that talk to the stored server PID directly — either
path works; the systemd path is preferred here because it's what gives us
supervision.)

## Reachability model

Multi-path fallback, evaluated in this order:

1. **Tailscale (preferred, primary path). VERIFIED WORKING.** This host
   (`vela0`) is joined to the `kenotron-ms` tailnet at `100.84.25.57`. Because
   `vela0` runs inside an Incus container behind NAT with no macvlan bridge,
   it has no other routable path onto the tailnet — running Tailscale
   directly on this host (rather than relying on a subnet-router elsewhere on
   the LAN) is the correct and necessary configuration here, not merely a
   convenience.

   Verified live, from the tailnet address itself (not `127.0.0.1`):
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://100.84.25.57:9099/v1/models
   # => 401 (unauthenticated, correctly rejected)

   curl -s -o /dev/null -w "%{http_code}\n" \
     -H "Authorization: Bearer $(grep AMPLIFIER_AGENT_HTTP_API_KEY ~/.amplifier/vela-agent-serve/env | cut -d= -f2)" \
     http://100.84.25.57:9099/v1/models
   # => 200 (authenticated, succeeds)
   ```
   `ops/agent-serve/health-check.sh` was re-run and passes end-to-end
   (`== HEALTHY ==`). Item 2 of this lane's success criteria is now `PASS`,
   closing out the residual left open at initial lane completion. Any device
   on the `kenotron-ms` tailnet can reach the service at
   `http://100.84.25.57:9099/v1/...` (or the MagicDNS name `vela0`, if
   MagicDNS is enabled on the tailnet) with no port-forwarding or public
   exposure required.

   To reproduce this setup on a new host:
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sudo sh
   sudo tailscale up --hostname=<name>
   # visit the printed login URL, authenticate under the target tailnet account
   tailscale ip -4   # note this address for clients
   ```

2. **LAN (fallback if Tailscale is down or not yet configured).** Since the
   unit binds `0.0.0.0:9099`, any device on the same local network can reach
   `http://<host-lan-ip>:9099/v1/...` directly, provided the host firewall
   allows port 9099 inbound from the LAN subnet. Find the LAN IP with
   `ip addr show | grep 'inet '`. This path has no built-in encryption beyond
   TLS-if-you-add-a-reverse-proxy — the bearer-token auth is the only
   protection, so prefer Tailscale whenever available.

3. **Direct/public (last resort, not recommended without a reverse proxy).**
   Exposing port 9099 directly to the public internet is explicitly
   discouraged by the CLI's own `--bind` help text and by this deployment's
   auth model (shared bearer secret only, no rate limiting, no TLS
   termination built in). If a client absolutely cannot use Tailscale or LAN,
   front the service with a proper TLS-terminating reverse proxy
   (e.g. Caddy or nginx) rather than exposing it raw.

**Client fallback strategy:** a client (e.g. the eventual phone app) should
attempt reachability in the order above — Tailscale hostname/IP first, LAN IP
second, and only fall through to a proxied public path if configured — mirroring
a standard multi-URL connectivity pattern (try the private, least-exposed path
first; widen only on failure).

## Residuals

- ~~Tailscale not installed on this sandboxed host~~ — **RESOLVED.** Tailscale
  is installed and authenticated on `vela0` (`100.84.25.57`, `kenotron-ms`
  tailnet); reachability verified live per the Reachability model section
  above. Item 2 is now `PASS`.
- **`loginctl enable-linger`** (to make the `systemd --user` session — and
  therefore this service — start automatically at boot before any interactive
  login) also requires privileges not available in this environment. On a
  real deployment target, run once as a privileged operator:
  ```bash
  sudo loginctl enable-linger <username>
  ```
  Without this, the service still restarts correctly across `systemctl --user`
  restarts and process crashes (verified), but a literal cold host reboot
  would require the user session to start first (e.g. via auto-login or
  linger) before systemd-user picks the unit back up. This is a one-time
  operator action, not a gap in the unit or scripts themselves.

## Provider drop-in-replacement note

Per this lane's scope-outs: `ExecStart` in `vela-agent-serve.service` invokes
the **stock** `amplifier-agent serve chat-completions` command with no
patches, wrappers, or forks. When Stage 3 (lane 3.1) introduces `vela-agentd`,
swapping the deployment target is a one-line change to `ExecStart` — the
supervision, auth, health-check, and redeploy tooling in this directory
require no changes.

## vela-agentd (production) — Lane MVP-B

`vela-agentd` (the lane 3.1 fork — event tee F1, real approval gate F2, C2
event/control route F3, C3 ledger proxy F4) is now running for real on `vela0`,
**alongside** the stock `vela-agent-serve` from lane 1.4 rather than replacing
it, on a **different port**.

**Why alongside, not replacing:** lane 1.4's stock service is a known-good,
currently verified path that other consumers (and lane 3.1's own item 7,
previously `PARTIAL`) may still reference. Running side-by-side on a
different port resolves that `PARTIAL` for real (a live competing systemd
service, exercised end-to-end) with zero risk to the already-working stock
deployment. A future lane can flip `vela-agent-serve.service` off and move
`vela-agentd` onto 9099 once the Android app (lane MVP-A) has cut over.

### Connection details for the Android app (lane MVP-A)

| | |
|---|---|
| **Base URL (Tailscale, preferred)** | `http://100.84.25.57:9098` |
| **Base URL (localhost, same host only)** | `http://127.0.0.1:9098` |
| **Auth header** | `Authorization: Bearer <token>` |
| **Auth token location** | `~/.amplifier/vela-agentd/env`, key `AMPLIFIER_AGENT_HTTP_API_KEY` |
| **Chat completions** | `POST /v1/chat/completions` (OpenAI-compatible; use a real model id from `GET /v1/models`, e.g. `claude-haiku-4-5-20251001` — the literal string `"amplifier"` is **not** a served model id on this fork) |
| **Models list** | `GET /v1/models` |
| **C2 event/control stream** | `GET /v1/events` (SSE; bearer-authenticated; emits tee'd kernel events — `thinking/delta`, `usage`, `tool/started`, `tool/completed`, etc.) |
| **C3 ledger proxy** | `GET/POST /ledger/*`, `GET /healthz/ledger` (proxies to the real `services/ledger` instance at `http://localhost:9199` on this host) |

To read the token programmatically:
```bash
grep AMPLIFIER_AGENT_HTTP_API_KEY ~/.amplifier/vela-agentd/env | cut -d= -f2
```

### Layout

```
ops/vela-agentd/
  vela-agentd.service   — systemd --user unit (port 9098, --config host-config.json)
  install.sh            — idempotent installer (unit + env file)
  redeploy.sh           — resync unit, restart, health-check
  health-check.sh       — active / 401 / 200 / C2-route checks (--port 9098)
```

Runtime state lives outside the repo:
- `~/.amplifier/vela-agentd/env` — shared-secret API key, workspace slug,
  ledger base URL, provider credential (`ANTHROPIC_API_KEY`, reused from the
  same credential already working for stock `vela-agent-serve` on this host).
- `~/.amplifier/vela-agentd/host-config.json` — **required** by this fork.
  Unlike stock `amplifier-agent`, `vela-agentd` does **not** auto-enable
  providers from resolvable credentials — `host_config.providers` must be
  declared explicitly or the server exits(2) at startup. Contents used here:
  ```json
  {
    "providers": {
      "anthropic": { "module": "anthropic" }
    }
  }
  ```
  (Note: the module id is the bare provider name — `anthropic`, not
  `provider-anthropic` — per this fork's config schema validator.)

### Verification performed (this lane)

1. **Service running under systemd --user**, alongside stock `agent-serve`:
   ```bash
   systemctl --user is-active vela-agentd.service   # => active
   systemctl --user is-active vela-agent-serve.service   # => active (both up)
   ```
2. **Real LLM provider credential** — reused `ANTHROPIC_API_KEY` already
   present in `~/.amplifier/vela-agent-serve/env`; the same key is present in
   `~/.amplifier/vela-agentd/env`. `GET /v1/models` returns 3 real Anthropic
   models loaded live from `api.anthropic.com`.
3. **`ops/vela-agentd/health-check.sh --port 9098`** passes locally
   (`127.0.0.1`) and was independently re-verified against the Tailscale
   address:
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://100.84.25.57:9098/v1/models
   # => 401 (unauthenticated, correctly rejected)
   curl -s -o /dev/null -w "%{http_code}\n" \
     -H "Authorization: Bearer $(grep AMPLIFIER_AGENT_HTTP_API_KEY ~/.amplifier/vela-agentd/env | cut -d= -f2)" \
     http://100.84.25.57:9098/v1/models
   # => 200
   ```
   The tailnet address (`100.84.25.57`, `kenotron-ms`) was re-confirmed
   unchanged from the value already documented above for stock `agent-serve`.
4. **C2 route smoke test** — connected a real SSE client to `/v1/events` on
   the running instance, concurrently issued a real
   `POST /v1/chat/completions` turn (model `claude-haiku-4-5-20251001`,
   prompt "Say the word PONG and nothing else"), and confirmed the SSE stream
   received real tee'd events during the turn: `thinking/delta` (x3) and
   `usage` (x2), correlated by a real `sessionId`/`turnId`. The chat
   completion itself returned `"PONG"` with real token usage and cost.
5. **C3 ledger proxy smoke test** — started `services/ledger` for real via
   `ops/ledger/install.sh` (systemd unit `vela-ledger-service.service`, port
   9199, previously not running on this host). Ran
   `services/vela-agentd/tests/test_ledger_proxy.py` (the fork's own harness,
   which skips automatically if no live ledger is reachable — it ran, it did
   not skip) against the real instance:
   ```
   tests/test_ledger_proxy.py::test_healthz_proxy_reaches_real_ledger PASSED
   tests/test_ledger_proxy.py::test_list_jobs_proxy_reaches_real_ledger PASSED
   tests/test_ledger_proxy.py::test_unauthenticated_ledger_proxy_rejected PASSED
   ```
   Confirms `vela-agentd`'s `/ledger/*` routes proxy to the real ledger
   service rather than reimplementing it locally.

### Residuals

- **`vela-agentd`'s uv-tool install was initially stale** (a prior partial
  install from Aug 17 was missing the `vela_agentd_cli` package entirely —
  `ModuleNotFoundError`). Fixed by `uv tool install --force --reinstall` from
  this worktree's `services/vela-agentd`. Unrelated to this lane's scope
  (deployment/config only) but recorded since it required intervention.
- **Stale `PreparedBundle` cache collision across worktrees.** The fork's
  bundle cache key is `(aaa_version, sha256(bundle.md content))` — since the
  vendored `bundle.md` content is identical across every checkout, a prepared
  bundle built from *lane 3.1's* worktree path (`~/workspace/vela-lane-3.1/`)
  was served from cache to this lane's install, baking in absolute agent
  `.md` paths that don't exist here (`FileNotFoundError` on
  `.../vela-lane-3.1/.../explorer.md`). Fixed by clearing
  `~/.amplifier-agent/cache/prepared/` once. This is a real cross-worktree
  cache-invalidation gap in the fork (the cache key doesn't account for the
  *installed* package's on-disk location, only bundle.md content) — worth a
  follow-up issue against lane 3.1's cache design, but out of this lane's
  file-ownership scope (`services/vela-agentd/src/` unmodified).
- **`host-config.json` was not part of lane 3.1's `install.sh` template.**
  This fork requires `host_config.providers` to be declared explicitly (no
  implicit registry, unlike stock `amplifier-agent`). Created
  `~/.amplifier/vela-agentd/host-config.json` by hand and wired `--config`
  into `ops/vela-agentd/vela-agentd.service`'s `ExecStart`. Recorded here
  rather than silently patching `install.sh` beyond what the lane's file
  ownership allows (`ops/vela-agentd/` config only — the service/install
  script changes made are within that ownership and are reflected in this
  repo).
- **Long-term production hosting (multiple hosts, failover, migrating stock
  `agent-serve` off port 9099 in favor of `vela-agentd`)** is out of scope —
  roadmap item #42, a separate future decision, per this lane's SCOPE-OUTS.
