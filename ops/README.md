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
