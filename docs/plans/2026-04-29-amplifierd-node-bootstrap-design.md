# amplifierd Node Bootstrap Design

## Goal

Enable Vela users to add a new remote node with nothing more than an SSH address — the Android app drives the entire installation of `amplifierd` and its Vela plugin over SSH, the same way VS Code Remote bootstraps its server.

## Background

Vela is an Android AI agent terminal that talks to remote nodes running `amplifierd` — a Python FastAPI daemon installed via `uv tool install git+https://github.com/microsoft/amplifierd`. The Android app already has a JSch SSH layer (`SshKeyManager`, `RunInNodeTool`, `SshNodeEntity`), a `NodeType` enum (`SSH | AMPLIFIERD`), and stubs for the `AMPLIFIERD` node type wired through `RunInNodeTool`.

What does not exist yet: anything that actually installs and configures `amplifierd` on a remote machine. Today a user has to SSH in manually, install `uv`, run `uv tool install`, write a settings file by hand, and either run the daemon in a terminal or hand-roll a service file. That is the gap this design closes.

Target platforms: **macOS and Linux** (both required — Mac mini at home, Ubuntu VM in the cloud, anything else on Tailscale).

## Approach

VS Code Remote SSH model: the Android app drives the entire bootstrap over an existing JSch SSH connection. User gives an SSH address → app installs `uv`, then `amplifierd` plus the Vela plugin, then writes config and a user-mode service file, then verifies — and the node comes up live. No manual steps on the server except one: pasting the app's public key into `~/.ssh/authorized_keys`.

Why this over alternatives:

- **vs. curl-pipe install script** — Hosted scripts require remote internet access and trust in a remote URL. They are also harder to drive unattended from the app with structured progress events.
- **vs. Docker** — Requires Docker on every node, which most Mac minis and bare Linux boxes lack. Adds overhead unnecessary for a small Python daemon.
- **vs. static binary distribution** — `amplifierd` is Python; `uv tool install` already gives us the equivalent of a single-command install with isolation, plus a clean plugin-injection point via `--with`.

The Android client already has every primitive it needs: JSch sessions, an RSA keypair under `SshKeyManager`, SFTP, and Room persistence. The bootstrap is composition over those primitives, not new infrastructure.

## Architecture

```
Android (Vela)                                   Remote Node (macOS or Linux)
┌─────────────────────────┐                      ┌──────────────────────────────┐
│  Add Node screen        │                      │                              │
│   (Screen 9)            │                      │   ~/.local/bin/uv            │
│      │                  │                      │   ~/.local/bin/amplifierd    │
│      ▼                  │                      │   ~/.amplifierd/             │
│  NodeBootstrapper       │  ── JSch / SFTP ──▶  │     settings.json            │
│   emits                 │                      │     projects.json            │
│   Flow<BootstrapEvent>  │                      │   service file:              │
│      │                  │                      │     launchd plist (macOS)    │
│      ▼                  │                      │     systemd user (Linux)     │
│  Bootstrap log sheet    │  ◀── stdout/stderr ──│   amplifierd serve           │
│      │                  │                      │     :8410                    │
│      ▼                  │                      │   plugin: amplifierd-vela    │
│  SshNodeRegistry        │                      │     auth, projects, caps,    │
│  promoteToAmplifierd()  │  ── HTTP /health ──▶ │     bundles                  │
│      │                  │                      │                              │
│      ▼                  │                      │                              │
│  Room: NodeType =       │  ── HTTP API ──────▶ │   FastAPI on 0.0.0.0:8410    │
│   AMPLIFIERD            │   x-amplifier-token  │                              │
└─────────────────────────┘                      └──────────────────────────────┘
```

Three pieces, owned in three places:

1. **Bootstrap orchestrator** (Android, Kotlin) — drives the SSH command sequence, surfaces progress.
2. **`amplifierd-vela` plugin** (Vela repo, Python) — runs inside `amplifierd` on the remote and adds the auth, project, capabilities, and bundle activation that the Android app depends on.
3. **Service unit** (per-platform, generated at bootstrap time) — keeps `amplifierd` running across reboots without root.

## Section 1: Bootstrap Flow

A single sequence driven entirely from the Android app over JSch. Every step is idempotent (re-running on an already-bootstrapped node performs an upgrade — see Section 5).

### Step 1 — Connect

Open a JSch session using the existing `SshKeyManager` RSA-3072 keypair. No changes to the SSH layer.

### Step 2 — Detect platform

Run `uname -sm` over the channel. Parse the result into a `Platform` enum:

| `uname -sm` output | Platform |
|--------------------|----------|
| `Darwin arm64`     | macOS / arm64 |
| `Darwin x86_64`    | macOS / x86_64 |
| `Linux x86_64`     | Linux / amd64 |
| `Linux aarch64`    | Linux / arm64 |

Anything else: hard-fail the bootstrap with `Unsupported platform: <output>`. This routes everything that follows — the service file format, log retrieval command, and home directory layout.

### Step 3 — Install `uv`

```sh
which uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh
```

`uv`'s installer works on both macOS and Linux, requires no `sudo`, and installs to `~/.local/bin`. After install, the bootstrap explicitly prepends `~/.local/bin` to `PATH` for the remainder of the SSH session so subsequent `uv` calls resolve.

### Step 4 — Install amplifierd + plugins

```sh
uv tool install --with amplifierd-vela git+https://github.com/microsoft/amplifierd
```

`amplifierd-vela` is the Vela-specific plugin (designed in Section 2). The `--with` flag composes it into the same isolated tool environment as `amplifierd` so its entry point gets discovered automatically at daemon startup.

If the user picked a different bundle in setup (Section 3), it gets added as an additional `--with` package — for example, `--with amplifierd-vela --with amplifierd-bundle-lifeos` for a personal node.

### Step 5 — Write config

Push `~/.amplifierd/settings.json` over SFTP. Contents are generated on Android and described in full in Section 4. The file gets written to a temp path first, then renamed atomically into place to avoid a half-written settings file if the SFTP transfer is interrupted.

### Step 6 — Install service

Platform-specific. Both targets use **user-mode** services (no `sudo`, runs as the SSH user, follows the same pattern as `self-managing-tool-patterns`).

**macOS** — write a launchd plist to `~/Library/LaunchAgents/com.vela.amplifierd.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC ...>
<plist version="1.0">
<dict>
  <key>Label</key><string>com.vela.amplifierd</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/{user}/.local/bin/amplifierd</string>
    <string>serve</string>
    <string>--host</string><string>0.0.0.0</string>
    <string>--port</string><string>8410</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/Users/{user}/.local/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>ANTHROPIC_API_KEY</key><string>{key}</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/Users/{user}/.amplifierd/stdout.log</string>
  <key>StandardErrorPath</key><string>/Users/{user}/.amplifierd/stderr.log</string>
</dict>
</plist>
```

Then activate:

```sh
launchctl bootout gui/$UID/com.vela.amplifierd 2>/dev/null || true
launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.vela.amplifierd.plist
launchctl kickstart -k gui/$UID/com.vela.amplifierd
```

**Linux** — write a systemd user unit to `~/.config/systemd/user/amplifierd.service`:

```ini
[Unit]
Description=Vela amplifierd daemon
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/amplifierd serve --host 0.0.0.0 --port 8410
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
Environment=ANTHROPIC_API_KEY={key}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

Then activate:

```sh
systemctl --user daemon-reload
systemctl --user enable --now amplifierd.service
```

For Linux, also run `loginctl enable-linger {user}` if not already enabled, so the service starts at boot rather than only at login.

### Step 7 — Verify

Poll over SSH every 2 seconds for up to 30 seconds:

```sh
curl -fsS http://127.0.0.1:8410/health
```

A 200 response with `{"status":"ok"}` ends polling and proceeds. Timeout triggers the failure-recovery flow in Section 5.

### Step 8 — Promote

On verified health, the app:

1. Calls `SshNodeRegistry.promoteToAmplifierd(nodeId, url, token)`.
2. The registry updates the Room row: `nodeType = AMPLIFIERD`, `url = http://<node-host>:8410`, `token = <generated-token>`, `bootstrapState = RUNNING`.
3. The bootstrap sheet emits `BootstrapEvent.Complete` and dismisses.
4. The Nodes list re-renders and the node appears with the live AMPLIFIERD badge.

The host portion of the URL is the same address the user typed in setup — typically a Tailscale `100.x.y.z` IP, sometimes a hostname. Open question 3 covers auto-detecting the Tailscale IP if the CLI is present.

## Section 2: The `amplifierd-vela` Plugin

A Python package that lives in the Vela repo at `plugins/amplifierd-vela/`. It is installed alongside `amplifierd` via `--with` and adds four things the Android app needs but the core daemon does not provide.

### Package layout

```
plugins/amplifierd-vela/
  pyproject.toml
  src/vela_plugin/
    __init__.py        # create_router(state) -> APIRouter
    auth.py            # token middleware
    projects.py        # POST /projects, GET /projects, GET /projects/:id/sessions
    capabilities.py    # GET /capabilities
    bundles.py         # bundle activation at startup
```

`pyproject.toml` registers the plugin via the standard `amplifierd.plugins` entry point:

```toml
[project.entry-points."amplifierd.plugins"]
vela = "vela_plugin:create_router"
```

`amplifierd` discovers and mounts the router automatically — no extra config beyond having the package present in the tool environment.

### 1. Token auth middleware (`auth.py`)

Reads `vela.auth_token` from `~/.amplifierd/settings.json` once at startup. Wires a FastAPI dependency that validates the `x-amplifier-token` header on every request to the plugin's routes. The `RunInNodeTool` on Android already sends this header, so the Android side requires no change.

Bypass rules:
- `127.0.0.1` and `::1` requests skip the check (so the daemon is still drivable locally without the token — same model as the existing local development flow).
- `/health` is unauthenticated regardless of source (so the bootstrap verify step in Section 1 can probe over `127.0.0.1:8410` without holding the token yet).

A missing or wrong token returns `401` with `{"error":"invalid_token"}`.

### 2. Project endpoints (`projects.py`)

`amplifierd` core is session-centric; the Android app thinks projects → sessions. The plugin adds a thin grouping layer:

| Endpoint | Behavior |
|----------|----------|
| `POST /projects` | Create a named project. Body: `{"name": "...", "description": "..."}`. Returns the project record with a generated UUID. |
| `GET /projects` | List all projects on this node. |
| `GET /projects/:id/sessions` | List sessions belonging to a project. Filters core's session list by `project_id`. |
| `DELETE /projects/:id` | Remove a project (sessions are detached, not deleted). |

Project metadata is persisted to `~/.amplifierd/projects.json` — a small, atomically-written JSON file (write to `projects.json.tmp`, then `os.replace`). Sessions reference a project by ID through a `project_id` field stored in core's session metadata via `state.session_manager`.

### 3. Capabilities endpoint (`capabilities.py`)

`GET /capabilities` returns:

```json
{
  "hostname": "mac-mini-home",
  "platform": "darwin/arm64",
  "amplifierd_version": "0.4.2",
  "vela_plugin_version": "0.1.0",
  "active_bundles": ["superpowers"],
  "available_tools": ["bash", "read_file", "write_file", "edit_file", "..."]
}
```

The Android app calls this when the user opens a node so the Node Detail screen knows which tools and bundles are live.

### 4. Bundle activation (`bundles.py`)

Reads the `bundles` list from settings (e.g. `["superpowers"]`). At plugin startup, calls `state.bundle_registry.load(name)` for each entry. Bundles are Amplifier YAML configs fetched from git by `BundleRegistry`.

Activation is logged so the bootstrap log sheet shows which bundles came up: `[vela] activated bundle: superpowers`.

If a bundle fails to load (network error, missing repo), the plugin logs the error but does not fail startup — the daemon comes up with whatever bundles loaded successfully, and the failed ones surface in `/capabilities` under an `errors` field.

## Section 3: Android Side

The existing **Connect a Node** screen (Screen 9 in `storyboard-ux.html`) becomes the entry point. Today it is URL-and-token entry only; it expands into a full SSH bootstrap flow.

### UI flow

**Step 1 — SSH credentials input.** Fields: host, port (default `22`), username. Below the form, a read-only **Public key** field shows the SSH public key from `SshKeyManager` with a copy button and a single line of instruction:

> Paste this into `~/.ssh/authorized_keys` on the node, then tap Continue.

This is the only manual step on the server, ever.

**Step 2 — Bundle selection.** A chip row (M3 8dp chips, per the design language):

| Chip | `--with` packages added | `bundles` written to settings |
|------|-------------------------|-------------------------------|
| `superpowers` *(default)* | `amplifierd-bundle-superpowers` | `["superpowers"]` |
| `lifeos` | `amplifierd-bundle-lifeos` | `["lifeos"]` |
| `tools-only` | none | `[]` |
| `custom` | (collected in a sub-sheet) | (collected in a sub-sheet) |

**Step 3 — API key.** A single field for `ANTHROPIC_API_KEY`. Stored only for the duration of the bootstrap; written into the service file's environment block and then discarded from Android memory. (Open question 2 covers whether to also collect keys for other providers.)

**Step 4 — Bootstrap progress sheet.** Tapping **Connect** opens a bottom sheet with a live monospace log stream. Each SSH command's stdout/stderr scrolls in as it runs — `uv` download progress, `uv tool install` package resolution, service activation output, health-check polls. Below the log: a step indicator showing which `BootstrapStep` is current and which have completed. Like VS Code's remote install panel, this is the reassuring part: the user sees exactly what the app is doing on the server, and a failure is never opaque.

**Step 5 — Promotion on success.** `BootstrapEvent.Complete` fires → `SshNodeRegistry.promoteToAmplifierd(nodeId, url, token)` swaps `NodeType.SSH` → `NodeType.AMPLIFIERD`, persists URL and token to Room. The sheet collapses; the node appears live in the Nodes list.

### New Kotlin code

**`NodeBootstrapper.kt`** — orchestrates the SSH command sequence. Constructor takes a `JSch` session factory and an `SshNodeRegistry`. Exposes:

```kotlin
fun bootstrap(
  host: String,
  port: Int,
  username: String,
  bundle: BundleChoice,
  anthropicKey: String,
): Flow<BootstrapEvent>
```

The flow emits events as the bootstrap progresses; the UI collects it and renders the log sheet.

**`BootstrapEvent`** — sealed class:

```kotlin
sealed class BootstrapEvent {
  data class Output(val line: String) : BootstrapEvent()
  data class StepStart(val step: BootstrapStep) : BootstrapEvent()
  data class StepComplete(val step: BootstrapStep) : BootstrapEvent()
  data class Failed(val step: BootstrapStep, val error: String, val logs: List<String>) : BootstrapEvent()
  data class Complete(val url: String, val token: String) : BootstrapEvent()
}
```

**`BootstrapStep`** — enum: `DETECT | INSTALL_UV | INSTALL_AMPLIFIERD | WRITE_CONFIG | INSTALL_SERVICE | VERIFY`.

**`SshNodeEntity`** — gain a new column:

```kotlin
val bootstrapState: BootstrapState  // PENDING | BOOTSTRAPPING | RUNNING | STALE
```

`PENDING` is the initial state after credentials are entered. `BOOTSTRAPPING` while the flow runs (and remains there if it fails — see Section 5). `RUNNING` after successful health check. `STALE` when a newer `amplifierd` version is available (Section 5 upgrade path).

**`SshNodeRegistry`** — gains:

```kotlin
suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String)
suspend fun markStale(nodeId: String)
```

Room migration: add `bootstrapState TEXT NOT NULL DEFAULT 'PENDING'` and reuse the existing `url` and `token` columns.

## Section 4: Configuration & Auth

Three things get written to the remote during bootstrap. The Android app pushes them all over SFTP between Step 5 (write config) and Step 6 (install service).

### `~/.amplifierd/settings.json`

```json
{
  "host": "0.0.0.0",
  "port": 8410,
  "log_level": "info",
  "bundles": ["superpowers"],
  "disabled_plugins": [],
  "vela": {
    "auth_token": "<32-byte-base64url-token-generated-on-android>"
  }
}
```

`host: "0.0.0.0"` is deliberate — Tailscale (or any other private network) needs the daemon listening on a non-localhost interface to reach it. The auth token (below) is what makes that safe.

The `vela` key is namespaced specifically for the plugin so it does not collide with anything `amplifierd` core or other plugins might want to read from settings later.

### Auth token generation

On the Android side, at the start of bootstrap:

```kotlin
val tokenBytes = ByteArray(32)
SecureRandom.getInstanceStrong().nextBytes(tokenBytes)
val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
```

This token is:
1. Written to `settings.json` under `vela.auth_token` (above).
2. Stored in `SshNodeEntity.token` in Room.
3. Sent on every subsequent request from `RunInNodeTool` as the `x-amplifier-token` header.

The plugin reads it from settings at startup and rejects requests without it (with the localhost and `/health` exemptions described in Section 2).

The token is generated fresh on every bootstrap run, including upgrades. This means re-bootstrapping a node rotates the token automatically — defense in depth if the old one ever leaked.

### LLM provider key

`amplifierd` runs the LLM on the remote node, so the remote needs `ANTHROPIC_API_KEY` available in the daemon's process environment. Rather than asking users to pre-configure environment variables on the server (the opposite of the "zero manual steps" goal), the Android app collects the key during node setup (Section 3, Step 3) and writes it directly into the service file's environment block:

- **launchd plist** — inside the `EnvironmentVariables` dict, alongside `PATH`.
- **systemd unit** — as an `Environment=ANTHROPIC_API_KEY={key}` line in the `[Service]` section.

This means: service restart picks up the key automatically; no `~/.bashrc`, `~/.zshrc`, or shell-profile editing is ever needed on the remote. The key lives in exactly one place per node, alongside the binary path and `PATH` itself.

The same pattern is documented in the `self-managing-tool-patterns` skill for `PATH` forwarding — extending it to provider keys is a natural fit.

## Section 5: Error Handling & Upgrades

### Idempotency

The bootstrap is safe to re-run. Every step is guarded so the second run becomes an in-place upgrade rather than a duplicate install or a broken half-state:

| Step | Guard |
|------|-------|
| Install `uv` | `which uv` check — skip install if present. |
| Install `amplifierd` | `uv tool list` check — if present, run `uv tool install --force ...` (Section 5 upgrade path). |
| Write config | Always overwrite (settings are regenerated each run; new token on every run). |
| Install service | Always overwrite (ensures the binary path stays correct after `uv` upgrades). |

A node that already has `amplifierd` running, when re-bootstrapped, ends up upgraded — same path, same service, refreshed binary, refreshed token.

### Failure recovery

Any `BootstrapEvent.Failed` leaves the node in `bootstrapState = BOOTSTRAPPING` rather than silently dropped or rolled back. The user sees the exact failed step, the error message, and the trailing log output in the bootstrap sheet. A **Retry** button re-runs the whole sequence — safe because of idempotency.

The node is **never** promoted to `NodeType.AMPLIFIERD` until the health check returns 200. There is no in-between state where the app thinks the daemon is live but it isn't.

### Service won't start

The most common silent failure: the service unit installs cleanly but the daemon refuses to start. Causes seen in practice: missing API key, Python version too old for `amplifierd`, port already in use, corrupted settings.

When the health check times out after 30 seconds, the bootstrap retrieves the last 20 lines of service logs over SSH and appends them to the log sheet:

- **Linux:** `journalctl --user -n 20 -u amplifierd --no-pager`
- **macOS:** read the tail of the launchd-redirected log files: `tail -n 20 ~/.amplifierd/stderr.log ~/.amplifierd/stdout.log`

The user sees the actual Python traceback or service error and can act on it without leaving the app.

### Upgrade path

When a newer version of `amplifierd` ships, the Android app surfaces it on each existing node:

1. The Nodes list shows a `STALE` badge on nodes whose recorded version (from the last `/capabilities` call) is behind the latest known version.
2. The Node Detail / Config screen (Screen 8) shows an **Update Node** button.
3. Tapping it runs the upgrade sequence:

```
stop service
  → uv tool install --force --with amplifierd-vela git+https://github.com/microsoft/amplifierd
  → regenerate service file       ← critical
  → reload service unit
  → start service
  → health verify
```

The service-file regeneration step is **load-bearing**. `uv tool install --force` reinstalls into a new isolated venv; the binary path changes (e.g. `~/.local/share/uv/tools/amplifierd/<hash>/bin/amplifierd`). Skipping the regen means the service keeps pointing at the old binary path, which still exists for a while but is no longer the one being upgraded. This is the same hazard the `self-managing-tool-patterns` skill calls out, and the upgrade path follows that skill's "regen + reload + verify" discipline strictly.

If the upgrade fails any step, the node returns to the previous version (the old binary path is still present until `uv` garbage-collects it) and surfaces the error in the same log sheet pattern as initial bootstrap.

## Open Questions

1. **Plugin distribution** — Should `amplifierd-vela` be published to PyPI, or distributed only as a local package via the Vela repo (e.g. `--with git+https://github.com/.../vela#subdirectory=plugins/amplifierd-vela`)? PyPI gives a cleaner install command and version pinning; local-only avoids the publish step during early development. Defer to first stable release: develop local, publish at v1.0.
2. **Provider key coverage** — Is `ANTHROPIC_API_KEY` the only key worth collecting at setup, or should the bootstrap also accept `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, etc., for users running multiple providers? Adding an "Advanced" expander in setup with optional fields keeps the default path simple while making the multi-provider case a non-event.
3. **Tailscale auto-detection** — During bootstrap, if `tailscale` CLI is present on the remote (`which tailscale`), should the app auto-fill the recorded URL with `tailscale ip -4` output rather than the SSH host the user typed? Most Vela nodes will be on Tailscale; pre-filling the Tailscale IP avoids a class of "I bootstrapped via local IP and now can't reach it from outside the LAN" mistakes. Recommended: yes, with the SSH host as fallback if `tailscale` is absent or returns no IP.
