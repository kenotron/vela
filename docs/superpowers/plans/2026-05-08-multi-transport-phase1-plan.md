# Multi-Transport Connectivity — Phase 1: amplifierd + Data Foundation

> **For execution:** Use `/execute-plan` mode or the subagent-driven-development recipe.

**Goal:** Add machine_id to amplifierd `/health`, start mDNS advertising, and lay the Android data foundation — `NodeEndpoint` sealed class, new fields on `SshNode`, DB migration v17→v18.

**Phase 2:** Resolution layer (EndpointResolver, MdnsDiscoveryService, ConnectivityPoller) and wiring into all callers.

**No TDD.** Verify via build + install + app/curl checks.

**Architecture:** amplifierd gains machine_id (hardware UUID via platform fallback chain) returned in `/health` and broadcasts itself via mDNS using the `zeroconf` library. The Android side adds `NodeEndpoint` (kotlinx.serialization sealed class), extends `SshNode`/`SshNodeEntity` with `machineId`+`endpoints`, and migrates the Room DB from v17→v18 with a backfill of existing `url`/`tailscale_url` columns into the new JSON endpoints column.

**Tech stack:** Python 3.13 / FastAPI / zeroconf (amplifierd plugin); Kotlin 2.0.0 / Room 2.6.1 / kotlinx.serialization-json 1.7.3 (Android).

**Design doc:** `docs/superpowers/specs/2026-05-07-multi-transport-connectivity-design.md`

---

## Codebase map (read before starting)

| File | Current state |
|---|---|
| `plugins/amplifierd-vela/src/vela_plugin/__init__.py` | `create_router(state)` — receives `app.state`, returns `APIRouter` |
| `plugins/amplifierd-vela/pyproject.toml` | deps: `fastapi>=0.100.0` only |
| `app/src/main/kotlin/com/vela/app/ssh/SshNode.kt` | Has `url`, `tailscaleUrl` fields. No `machineId`/`endpoints`. |
| `app/src/main/kotlin/com/vela/app/data/db/SshNodeEntity.kt` | Room entity v17. No `machine_id`/`endpoints` columns. |
| `app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt` | `promoteToAmplifierd` takes `(id, type, url, tailscaleUrl, token, status)` |
| `app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt` | `toDomain()`/`toEntity()` map url/tailscaleUrl. No machineId/endpoints. |
| `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` | `version = 17`. Migrations through `MIGRATION_16_17`. |
| `app/src/main/kotlin/com/vela/app/di/AppModule.kt` | `.addMigrations(... MIGRATION_16_17)` |
| `gradle/libs.versions.toml` | No `kotlinx-serialization` entries. |
| `app/build.gradle.kts` | No `kotlin("plugin.serialization")` plugin. |

---

## Implementation note: wiring machine_id into /health

The amplifierd plugin API (`create_router(state)`) only provides `app.state`, not the `FastAPI` app object. The core `/health` route is registered before plugins load, so the plugin cannot override it via a normal router addition (first-registered route wins).

**The approach used in Task 4:** use Python's gc module to navigate from `state` (which is `app.state`) back to the `FastAPI` app, then insert a route at position 0 in `app.routes`. FastAPI's router iterates routes in order on every request — a position-0 route takes priority over the existing core `/health` route. This is a startup-once operation (cheap) and clearly documented in the code.

---

## Task 1: Add zeroconf dependency

**Files:**
- Modify: `plugins/amplifierd-vela/pyproject.toml`

**Step 1: Implement**

```toml
[project]
    name = "amplifierd-vela"
    version = "0.1.0"
    description = "Vela-specific plugin for amplifierd: auth, projects, capabilities, bundles."
    requires-python = ">=3.11"
    dependencies = ["fastapi>=0.100.0", "zeroconf>=0.131.0"]
```

Only the `dependencies` line changes — add `"zeroconf>=0.131.0"`.

**Step 2: Build**

```bash
cd /Users/ken/workspace/vela/plugins/amplifierd-vela
uv pip install . --python ~/.local/share/uv/tools/amplifierd/bin/python
```

Expected: `Successfully installed amplifierd-vela-0.1.0 zeroconf-...` (no errors).

**Step 3: Verify**

```bash
~/.local/share/uv/tools/amplifierd/bin/python -c "import zeroconf; print(zeroconf.__version__)"
```

Expected: a version string printed, no ImportError.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela
git add plugins/amplifierd-vela/pyproject.toml
git commit -m "feat(amplifierd-vela): add zeroconf dependency for mDNS advertising"
```

---

## Task 2: Create machine_id.py

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/machine_id.py`

**Step 1: Implement**

```python
"""Platform-stable machine identity for amplifierd mDNS and /health.

Priority chain:
  macOS  → IOPlatformUUID via ioreg (hardware-bound, survives reboots)
  Linux  → /sys/class/dmi/id/product_uuid (firmware-bound, may need root)
           → /etc/machine-id (distro-assigned, persists across reboots)
  Any    → ~/.amplifier/machine-id (generated once, persisted)
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import uuid


def get_machine_id() -> str:
    """Return a stable machine UUID, generating and persisting one if needed."""
    if sys.platform == "darwin":
        result = subprocess.run(
            ["ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
            capture_output=True,
            text=True,
        )
        for line in result.stdout.splitlines():
            if "IOPlatformUUID" in line:
                # Line format: "IOPlatformUUID" = "65E872B0-3343-5255-8409-8C2C13974937"
                return line.split('"')[-2]

    dmi = pathlib.Path("/sys/class/dmi/id/product_uuid")
    if dmi.exists():
        try:
            return dmi.read_text().strip()
        except PermissionError:
            pass

    machine_id = pathlib.Path("/etc/machine-id")
    if machine_id.exists():
        return machine_id.read_text().strip()

    persist = pathlib.Path(
        os.environ.get("AMPLIFIER_HOME", str(pathlib.Path.home() / ".amplifier"))
    ) / "machine-id"
    if persist.exists():
        return persist.read_text().strip()

    new_id = str(uuid.uuid4())
    persist.parent.mkdir(parents=True, exist_ok=True)
    persist.write_text(new_id)
    return new_id
```

**Step 2: Build** (smoke-check the module loads)

```bash
~/.local/share/uv/tools/amplifierd/bin/python \
  -c "import sys; sys.path.insert(0, 'plugins/amplifierd-vela/src'); \
      from vela_plugin.machine_id import get_machine_id; print(get_machine_id())"
```

Expected: a UUID string printed (e.g. `65E872B0-3343-5255-8409-8C2C13974937` on macOS).

**Step 3: Verify**

Output is a non-empty UUID-like string. On macOS it should match what you see in System Information → Hardware Overview → Hardware UUID.

**Step 4: Commit**

```bash
git add plugins/amplifierd-vela/src/vela_plugin/machine_id.py
git commit -m "feat(amplifierd-vela): machine_id platform fallback chain"
```

---

## Task 3: Create mdns_service.py

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/mdns_service.py`

**Step 1: Implement**

```python
"""mDNS service advertising for amplifierd.

Registers _amplifierd._tcp.local. so Android devices on the same network
can discover this node without a known IP address.

Service record:
  type:       _amplifierd._tcp.local.
  name:       <label>._amplifierd._tcp.local.
  port:       8410 (or configured port)
  TXT:        machine_id=<uuid>, version=0.1.0
"""
from __future__ import annotations

import logging
import socket

logger = logging.getLogger(__name__)


class AmplifierdMdnsService:
    """Register/deregister an amplifierd mDNS service advertisement."""

    def __init__(self, machine_id: str, port: int = 8410) -> None:
        self.machine_id = machine_id
        self.port = port
        self._zeroconf = None
        self._info = None

    def start(self, label: str = "amplifierd") -> None:
        """Register the mDNS service. Blocks briefly until registration completes."""
        try:
            from zeroconf import ServiceInfo, Zeroconf

            hostname = socket.gethostname()
            try:
                local_ip = socket.gethostbyname(hostname)
            except socket.gaierror:
                # Fallback: find a non-loopback IPv4 address
                local_ip = _get_local_ip()

            self._zeroconf = Zeroconf()
            self._info = ServiceInfo(
                "_amplifierd._tcp.local.",
                f"{label}._amplifierd._tcp.local.",
                addresses=[socket.inet_aton(local_ip)],
                port=self.port,
                properties={
                    "machine_id": self.machine_id,
                    "version": "0.1.0",
                },
            )
            self._zeroconf.register_service(self._info)
            logger.info(
                "amplifierd mDNS registered: %s on %s:%d (machine_id=%s)",
                f"{label}._amplifierd._tcp.local.",
                local_ip,
                self.port,
                self.machine_id,
            )
        except Exception:
            logger.warning("Failed to register mDNS service", exc_info=True)

    def stop(self) -> None:
        """Deregister the mDNS service."""
        try:
            if self._zeroconf and self._info:
                self._zeroconf.unregister_service(self._info)
                self._zeroconf.close()
                logger.info("amplifierd mDNS deregistered")
        except Exception:
            logger.warning("Failed to deregister mDNS service", exc_info=True)
        finally:
            self._zeroconf = None
            self._info = None


def _get_local_ip() -> str:
    """Return the best-guess local IPv4 address (non-loopback)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
```

**Step 2: Build**

```bash
~/.local/share/uv/tools/amplifierd/bin/python \
  -c "import sys; sys.path.insert(0, 'plugins/amplifierd-vela/src'); \
      from vela_plugin.mdns_service import AmplifierdMdnsService; print('OK')"
```

Expected: `OK` printed, no ImportError.

**Step 3: Verify**

Module imports cleanly. Full functional test happens in Task 4.

**Step 4: Commit**

```bash
git add plugins/amplifierd-vela/src/vela_plugin/mdns_service.py
git commit -m "feat(amplifierd-vela): AmplifierdMdnsService for mDNS advertising"
```

---

## Task 4: Wire machine_id + mDNS into plugin \_\_init\_\_.py

**Files:**
- Modify: `plugins/amplifierd-vela/src/vela_plugin/__init__.py`

**Step 1: Implement**

Replace the entire file:

```python
"""amplifierd-vela plugin entry point."""
from __future__ import annotations

import gc
import logging
import time
from typing import Any

import amplifierd
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

__version__ = "0.1.0"

logger = logging.getLogger(__name__)


def create_router(state: Any) -> APIRouter:
    """Plugin entry point. Activates bundles and mounts auth + endpoints."""
    # Imported lazily so test fixtures can monkeypatch DEFAULT_PATH before load.
    from .auth import make_require_token
    from .bundles import activate_bundles
    from .capabilities import make_capabilities_router
    from .machine_id import get_machine_id
    from .mdns_service import AmplifierdMdnsService
    from .projects import make_projects_router
    from .settings import load_settings
    from .steer import make_steer_router

    settings = load_settings()
    activate_bundles(state, settings)

    # ── machine_id ──────────────────────────────────────────────────────────
    machine_id = get_machine_id()
    state.machine_id = machine_id
    logger.info("amplifierd-vela: machine_id = %s", machine_id)

    # Inject machine_id into /health by inserting a priority route at position 0.
    # The plugin API only provides app.state (not the FastAPI app), so we use gc
    # to navigate: state → app.__dict__ → app. Route insertion is dynamic —
    # FastAPI iterates app.routes on each request, first match wins.
    _inject_health_override(state, machine_id)

    # ── mDNS ─────────────────────────────────────────────────────────────────
    import socket
    label = socket.gethostname()
    mdns = AmplifierdMdnsService(machine_id=machine_id)
    mdns.start(label=label)
    # Store for potential cleanup (zeroconf also registers atexit handler internally)
    state.mdns_service = mdns

    # ── Auth + routes ─────────────────────────────────────────────────────────
    require_token = make_require_token(settings)

    router = APIRouter()
    router.include_router(
        make_projects_router(state),
        dependencies=[Depends(require_token)],
    )
    router.include_router(
        make_capabilities_router(state),
        dependencies=[Depends(require_token)],
    )
    router.include_router(
        make_steer_router(state),
        dependencies=[Depends(require_token)],
    )
    return router


# ── Health override ────────────────────────────────────────────────────────────

def _inject_health_override(state: Any, machine_id: str) -> None:
    """Insert a /health route at position 0 to add machine_id to the response.

    Why gc? The amplifierd plugin API passes app.state, not the FastAPI app.
    app.state is stored in app.__dict__['state'], so:
      gc.get_referrers(state)       → includes app.__dict__
      gc.get_referrers(app.__dict__) → includes app
    Route insertion is safe post-startup: FastAPI's router iterates self.routes
    dynamically on each request, so position-0 routes take immediate effect.
    """
    from fastapi import FastAPI

    app: FastAPI | None = None
    for ref in gc.get_referrers(state):
        if not isinstance(ref, dict):
            continue
        for owner in gc.get_referrers(ref):
            if isinstance(owner, FastAPI):
                app = owner
                break
        if app is not None:
            break

    if app is None:
        logger.warning(
            "amplifierd-vela: could not locate FastAPI app from state; "
            "machine_id will NOT appear in /health"
        )
        return

    _machine_id = machine_id  # capture for closure

    async def _health_with_machine_id(request: Request) -> JSONResponse:
        """Replacement /health that includes machine_id."""
        start_time: float = getattr(request.app.state, "start_time", time.time())
        uptime = round(time.time() - start_time, 2)
        session_manager = getattr(request.app.state, "session_manager", None)
        active = len(session_manager.list_sessions()) if session_manager else 0
        try:
            import amplifier_core
            rust_engine = bool(getattr(amplifier_core, "rust_available", False))
        except Exception:
            rust_engine = False
        return JSONResponse({
            "status": "healthy",
            "version": amplifierd.__version__,
            "uptime_seconds": uptime,
            "active_sessions": active,
            "rust_engine": rust_engine,
            "machine_id": _machine_id,
        })

    health_route = APIRoute("/health", _health_with_machine_id, methods=["GET"])
    app.routes.insert(0, health_route)
    logger.info("amplifierd-vela: /health override installed (machine_id injected)")
```

**Step 2: Build** (reinstall + restart amplifierd)

```bash
cd /Users/ken/workspace/vela/plugins/amplifierd-vela
uv pip install . --python ~/.local/share/uv/tools/amplifierd/bin/python
launchctl bootout gui/$(id -u)/com.vela.amplifierd 2>/dev/null || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.vela.amplifierd.plist
sleep 3
```

Expected: no errors from launchctl.

**Step 3: Verify**

```bash
curl -s http://10.0.0.143:8410/health | python3 -m json.tool
```

Expected output (values will differ):
```json
{
    "status": "healthy",
    "version": "0.1.0",
    "uptime_seconds": 1.23,
    "active_sessions": 0,
    "rust_engine": false,
    "machine_id": "65E872B0-3343-5255-8409-8C2C13974937"
}
```

The `machine_id` field MUST be present. Also verify mDNS is advertising:

```bash
dns-sd -B _amplifierd._tcp local. &
sleep 3
kill %1
```

Expected: a line like `Add  3  4 local _amplifierd._tcp <hostname>._amplifierd._tcp.` in the output.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela
git add plugins/amplifierd-vela/src/vela_plugin/__init__.py
git commit -m "feat(amplifierd-vela): machine_id in /health + mDNS advertising"
```

---

## Task 5: Add kotlinx.serialization to Android build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Step 1: Implement**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
kotlinxSerializationJson = "1.7.3"
```

Under `[libraries]`, add after the existing kotlinx entries:
```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
```

Under `[plugins]`, add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In `app/build.gradle.kts`, in the `plugins { }` block, add after the existing kotlin plugins:
```kotlin
alias(libs.plugins.kotlin.serialization)
```

In the `dependencies { }` block, add after the coroutines entries:
```kotlin
// kotlinx.serialization — JSON for NodeEndpoint sealed class DB storage
implementation(libs.kotlinx.serialization.json)
```

**Step 2: Build**

```bash
cd /Users/ken/workspace/vela
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` in the last few lines.

**Step 3: Verify**

Build succeeds with no serialization-related errors.

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(android): add kotlinx.serialization-json for NodeEndpoint"
```

---

## Task 6: Create NodeEndpoint.kt

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ssh/NodeEndpoint.kt`

**Step 1: Implement**

```kotlin
package com.vela.app.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A typed transport endpoint for reaching an amplifierd node.
 *
 * Serialized as JSON in the [SshNodeEntity.endpoints] column:
 *   {"type":"direct","url":"http://10.0.0.50:8410"}
 *   {"type":"tailscale","url":"http://100.x.x.x:8410"}
 *   {"type":"mdns","serviceName":"ken-mac._amplifierd._tcp.local."}
 *
 * The [Mdns] endpoint stores only the mDNS service name — no IP address.
 * The resolved IP is always ephemeral, held in memory by MdnsDiscoveryService.
 */
@Serializable
sealed class NodeEndpoint {

    /** Explicit IP/URL — set during SSH bootstrap or manual entry. */
    @Serializable
    @SerialName("direct")
    data class Direct(val url: String) : NodeEndpoint()

    /** Tailscale IP — tried when not on the same LAN. */
    @Serializable
    @SerialName("tailscale")
    data class Tailscale(val url: String) : NodeEndpoint()

    /**
     * mDNS service name — no stored IP, resolved live by [MdnsDiscoveryService].
     * Persisted once a service is matched to this node so future app starts
     * know to expect mDNS for this node.
     */
    @Serializable
    @SerialName("mdns")
    data class Mdns(val serviceName: String) : NodeEndpoint()
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

**Step 3: Verify**

Build succeeds. No compilation errors.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ssh/NodeEndpoint.kt
git commit -m "feat(android): NodeEndpoint sealed class with kotlinx.serialization"
```

---

## Task 7: Update SshNode.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/SshNode.kt`

**Step 1: Implement**

Replace the entire file:

```kotlin
package com.vela.app.ssh

import java.util.UUID

enum class NodeType { SSH, AMPLIFIERD }

/**
 * Lifecycle of an amplifierd-capable node.
 *
 * UNPROVISIONED → fresh SSH node, never bootstrapped.
 * BOOTSTRAPPING → bootstrap in progress.
 * RUNNING       → amplifierd is live and health-checked.
 * STALE         → running but a newer amplifierd version is available.
 * FAILED        → bootstrap attempted but failed; retry required.
 */
enum class BootstrapStatus {
    UNPROVISIONED,
    BOOTSTRAPPING,
    RUNNING,
    STALE,
    FAILED,
}

data class SshNode(
    val id:       String = UUID.randomUUID().toString(),
    val label:    String,
    /** Ordered list of IPs/hostnames for SSH nodes (primary + fallbacks). */
    val hosts:    List<String> = emptyList(),
    val port:     Int    = 22,
    val username: String = "",
    val addedAt:  Long   = System.currentTimeMillis(),
    /** Node type — SSH or Amplifierd daemon. */
    val type:     NodeType = NodeType.SSH,

    // ── Deprecated transport fields ───────────────────────────────────────────
    // These are kept readable for backward-compat mapping but are no longer
    // written going forward. Use [endpoints] instead.
    /** @deprecated Use endpoints list instead. Kept for migration reads. */
    val url:         String = "",
    /** @deprecated Use endpoints list instead. Kept for migration reads. */
    val tailscaleUrl: String = "",

    // ── New multi-transport fields ────────────────────────────────────────────
    /** amplifierd x-amplifier-token shared secret. */
    val token:    String = "",
    /**
     * Stable hardware identity from /health machine_id.
     * Used to match mDNS-discovered services to saved nodes.
     */
    val machineId: String = "",
    /**
     * Ordered list of transport endpoints for this node.
     * Phase 2 EndpointResolver tries these in priority order:
     * Mdns (LAN-fastest) → Tailscale → Direct.
     */
    val endpoints: List<NodeEndpoint> = emptyList(),

    /** Bootstrap lifecycle state. New SSH nodes default to UNPROVISIONED. */
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.UNPROVISIONED,
    /** Workspace directory used as cwd when amplifierd runs sessions on this node. */
    val workspaceDir: String = "~",
) {
    val primaryHost: String get() = hosts.firstOrNull() ?: ""
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. (The entity and registry don't know about the new fields yet, but the domain class compiles cleanly.)

**Step 3: Verify**

Build succeeds.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ssh/SshNode.kt
git commit -m "feat(android): SshNode gains machineId + endpoints; url/tailscaleUrl deprecated"
```

---

## Task 8: Update SshNodeEntity.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/SshNodeEntity.kt`

**Step 1: Implement**

Replace the entire file:

```kotlin
package com.vela.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_nodes")
data class SshNodeEntity(
    @PrimaryKey val id: String,
    val label:    String,
    /** Comma-separated ordered list of IPs/hostnames (SSH nodes). */
    val hosts:    String,
    val port:     Int,
    val username: String,
    val addedAt:  Long,
    /** "ssh" or "amplifierd". Default "ssh" for backward compat. */
    val nodeType: String = "ssh",
    /** amplifierd base URL. Deprecated — use endpoints column instead. */
    val url:      String = "",
    /** amplifierd token. Empty for SSH nodes. */
    val token:    String = "",
    /** Tailscale IP URL. Deprecated — use endpoints column instead. */
    @ColumnInfo(name = "tailscale_url") val tailscaleUrl: String = "",
    /** BootstrapStatus enum name; default "UNPROVISIONED" for existing rows. */
    val bootstrapStatus: String = "UNPROVISIONED",
    /** Workspace directory used as cwd when amplifierd runs sessions. */
    @ColumnInfo(name = "workspace_dir") val workspaceDir: String = "~",

    // ── v18 columns ─────────────────────────────────────────────────────────
    /** Stable hardware UUID from /health machine_id. Empty for pre-v18 rows. */
    @ColumnInfo(name = "machine_id") val machineId: String = "",
    /**
     * JSON array of NodeEndpoint objects.
     * Format: [{"type":"direct","url":"..."},{"type":"tailscale","url":"..."}]
     * Backfilled from url + tailscale_url during MIGRATION_17_18.
     * Empty array "[]" for SSH-only nodes.
     */
    val endpoints: String = "[]",
)
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

⚠️ **Expected: BUILD FAILURE** — Room detects entity schema changed (v17 doesn't have `machine_id`/`endpoints`) but `VelaDatabase.version` is still 17. This is correct and expected. Task 10 fixes it by bumping the version and adding the migration.

**Step 3: Verify**

The build error should mention Room and a schema mismatch. Confirm the error message relates to Room version check, not a Kotlin compilation error.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/data/db/SshNodeEntity.kt
git commit -m "feat(android): SshNodeEntity adds machine_id + endpoints columns (v18)"
```

---

## Task 9: Update SshNodeDao.kt + SshNodeRegistry.kt

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt`

### Part A — SshNodeDao.kt

**Step 1: Implement**

Replace the entire file:

```kotlin
package com.vela.app.data.db

    import androidx.room.*
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface SshNodeDao {
        @Query("SELECT * FROM ssh_nodes ORDER BY addedAt ASC")
        fun getAllNodes(): Flow<List<SshNodeEntity>>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insert(node: SshNodeEntity)

        @Query("DELETE FROM ssh_nodes WHERE id = :id")
        suspend fun delete(id: String)

        @Query("SELECT * FROM ssh_nodes WHERE id = :id")
        suspend fun getById(id: String): SshNodeEntity?

        /** Update only the bootstrap lifecycle column. */
        @Query("UPDATE ssh_nodes SET bootstrapStatus = :status WHERE id = :id")
        suspend fun updateBootstrapStatus(id: String, status: String)

        /**
         * Promote an SSH node to an amplifierd node in a single statement: flips
         * nodeType, sets url + token, writes machineId + endpoints JSON, and marks
         * bootstrapStatus RUNNING.
         */
        @Query("""
            UPDATE ssh_nodes
            SET nodeType = :type,
                url = :url,
                tailscale_url = :tailscaleUrl,
                token = :token,
                bootstrapStatus = :status,
                machine_id = :machineId,
                endpoints = :endpoints
            WHERE id = :id
        """)
        suspend fun promoteToAmplifierd(
            id: String,
            type: String,
            url: String,
            tailscaleUrl: String,
            token: String,
            status: String,
            machineId: String,
            endpoints: String,
        )

        /** Update editable connection fields without touching token / bootstrapStatus / url. */
        @Query("UPDATE ssh_nodes SET label = :label, hosts = :hosts, port = :port, username = :username, workspace_dir = :workspaceDir WHERE id = :id")
        suspend fun updateConnection(id: String, label: String, hosts: String, port: Int, username: String, workspaceDir: String)
    }
```

### Part B — SshNodeRegistry.kt

Replace the entire file:

```kotlin
package com.vela.app.ssh

import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton


/** kotlinx.serialization Json instance for NodeEndpoint list encoding/decoding. */
private val endpointJson = Json {
    classDiscriminator = "type"   // matches the JSON format: {"type":"direct",...}
    ignoreUnknownKeys = true      // forward-compatible: unknown subtypes become empty list
}


@Singleton
open class SshNodeRegistry @Inject constructor(private val dao: SshNodeDao) {

    fun allFlow(): Flow<List<SshNode>> =
        dao.getAllNodes().map { it.map { e -> e.toDomain() } }

    @Volatile var cache: List<SshNode> = emptyList()
    fun updateCache(nodes: List<SshNode>) { cache = nodes }

    fun findByLabel(label: String): SshNode? =
        cache.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: cache.firstOrNull { it.primaryHost.equals(label, ignoreCase = true) }

    fun allSync(): List<SshNode> = cache

    suspend fun addNode(node: SshNode)    = dao.insert(node.toEntity())
    suspend fun updateNode(node: SshNode) = dao.insert(node.toEntity())
    suspend fun removeNode(id: String)    = dao.delete(id)

    /** Update editable connection fields (label, host, port, username, workspaceDir) in-place. */
    suspend fun updateConnection(node: SshNode) {
        dao.updateConnection(
            id           = node.id,
            label        = node.label,
            hosts        = node.hosts.joinToString(","),
            port         = node.port,
            username     = node.username,
            workspaceDir = node.workspaceDir,
        )
    }

    // ── Bootstrap-lifecycle writers ───────────────────────────────────────────

    /**
     * Promote an SSH node to an amplifierd node.
     * Builds initial [NodeEndpoint] list from url/tailscaleUrl and persists it.
     *
     * [machineId] defaults to "" — Phase 2 NodeBootstrapper will pass the real value
     * once it reads machine_id from the /health response.
     */
    open suspend fun promoteToAmplifierd(
        nodeId: String,
        url: String,
        tailscaleUrl: String = "",
        token: String,
        machineId: String = "",
    ) {
        val initialEndpoints = buildList<NodeEndpoint> {
            if (tailscaleUrl.isNotBlank()) add(NodeEndpoint.Tailscale(tailscaleUrl))
            if (url.isNotBlank()) add(NodeEndpoint.Direct(url))
        }
        val endpointsJson = endpointJson.encodeToString(initialEndpoints)
        dao.promoteToAmplifierd(
            id           = nodeId,
            type         = "amplifierd",
            url          = url,
            tailscaleUrl = tailscaleUrl,
            token        = token,
            status       = BootstrapStatus.RUNNING.name,
            machineId    = machineId,
            endpoints    = endpointsJson,
        )
    }

    /** Flag a running amplifierd node as stale (newer version available). */
    suspend fun markStale(nodeId: String) {
        dao.updateBootstrapStatus(nodeId, BootstrapStatus.STALE.name)
    }

    /** Update only the bootstrap lifecycle column. */
    open suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
        dao.updateBootstrapStatus(nodeId, status.name)
    }

    // ── Entity ↔ Domain mapping ───────────────────────────────────────────────

    private fun SshNodeEntity.toDomain() = SshNode(
        id              = id,
        label           = label,
        hosts           = hosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
        port            = port,
        username        = username,
        addedAt         = addedAt,
        type            = if (nodeType == "amplifierd") NodeType.AMPLIFIERD else NodeType.SSH,
        url             = url,
        tailscaleUrl    = tailscaleUrl,
        token           = token,
        machineId       = machineId,
        endpoints       = parseEndpoints(endpoints),
        bootstrapStatus = parseBootstrapStatus(bootstrapStatus),
        workspaceDir    = workspaceDir,
    )

    private fun SshNode.toEntity() = SshNodeEntity(
        id              = id,
        label           = label,
        hosts           = hosts.joinToString(","),
        port            = port,
        username        = username,
        addedAt         = addedAt,
        nodeType        = if (type == NodeType.AMPLIFIERD) "amplifierd" else "ssh",
        url             = url,
        tailscaleUrl    = tailscaleUrl,
        token           = token,
        machineId       = machineId,
        endpoints       = endpointJson.encodeToString(endpoints),
        bootstrapStatus = bootstrapStatus.name,
        workspaceDir    = workspaceDir,
    )

    private fun parseEndpoints(json: String): List<NodeEndpoint> =
        runCatching { endpointJson.decodeFromString<List<NodeEndpoint>>(json) }
            .getOrDefault(emptyList())

    /** Tolerant parse — unknown / corrupt strings fall back to UNPROVISIONED. */
    private fun parseBootstrapStatus(raw: String): BootstrapStatus =
        runCatching { BootstrapStatus.valueOf(raw) }.getOrDefault(BootstrapStatus.UNPROVISIONED)
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

⚠️ **Still expected to fail** with the Room version mismatch error (from Task 8). The Kotlin compilation of the DAO and registry should succeed; only Room's schema validation will still complain. Task 10 resolves this.

**Step 3: Verify**

The failure is Room's schema validation, not a Kotlin compilation error. If you see `java.lang.IllegalStateException: Room cannot verify the data integrity` or similar, that's correct — proceed to Task 10.

**Step 4: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt \
  app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt
git commit -m "feat(android): SshNodeDao + SshNodeRegistry wire machineId/endpoints"
```

---

## Task 10: DB Migration v17→v18 + bump version

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt`
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

### Part A — VelaDatabase.kt

**Step 1a: Implement**

Two changes in `VelaDatabase.kt`:

1. Bump `version = 17` → `version = 18` in the `@Database` annotation.
2. Add `MIGRATION_17_18` as a new top-level val at the top of the migrations section (before `MIGRATION_16_17`).

The exact diff:

```kotlin
// Change version in @Database:
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SshNodeEntity::class,
        TurnEntity::class,
        TurnEventEntity::class,
        VaultEntity::class,
        VaultEmbeddingEntity::class,
        GitHubIdentityEntity::class,
        MiniAppRegistryEntity::class,
        MiniAppDocumentEntity::class,
    ],
    version = 18,          // ← was 17
    exportSchema = true,
)
```

Then add this new migration val BEFORE `MIGRATION_16_17` in the file:

```kotlin
/**
 * v17→v18: add machine_id + endpoints columns to ssh_nodes.
 *
 * machine_id: stable hardware UUID from amplifierd /health (empty for pre-v18 rows).
 * endpoints:  JSON array of NodeEndpoint objects, backfilled from existing
 *             url + tailscale_url columns so existing nodes remain reachable.
 *
 * Backfill order: Tailscale first (tried first by EndpointResolver),
 * then Direct. Old url + tailscale_url columns are kept but no longer written.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN machine_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN endpoints TEXT NOT NULL DEFAULT '[]'")

        // Backfill endpoints from existing url + tailscale_url columns
        val cursor = db.query("SELECT id, url, tailscale_url FROM ssh_nodes")
        while (cursor.moveToNext()) {
            val id           = cursor.getString(0)
            val url          = cursor.getString(1) ?: ""
            val tailscaleUrl = cursor.getString(2) ?: ""
            val parts = buildList<String> {
                if (tailscaleUrl.isNotBlank())
                    add("""{"type":"tailscale","url":"$tailscaleUrl"}""")
                if (url.isNotBlank())
                    add("""{"type":"direct","url":"$url"}""")
            }
            val json = "[${parts.joinToString(",")}]"
            db.execSQL("UPDATE ssh_nodes SET endpoints = ? WHERE id = ?", arrayOf(json, id))
        }
        cursor.close()
    }
}
```

### Part B — AppModule.kt

Find the `addMigrations(...)` call and add `MIGRATION_17_18`:

```kotlin
Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
    .addMigrations(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
        MIGRATION_17_18,    // ← add this
    )
    .build()
```

**Step 2: Build**

```bash
cd /Users/ken/workspace/vela
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. Room KSP will generate `app/schemas/com.vela.app.data.db.VelaDatabase/18.json` — commit this file too.

**Step 3: Install + Verify**

```bash
DEVICE=$(./scripts/vela-device)
adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $DEVICE shell am start --user 0 -n com.vela.app/.MainActivity
```

Wait ~5 seconds for the app to start and Room to run the migration. Then verify the schema:

```bash
# Check new columns exist
adb -s $DEVICE shell run-as com.vela.app \
  sqlite3 /data/data/com.vela.app/databases/vela_database \
  ".schema ssh_nodes"
```

Expected: the schema includes `machine_id TEXT NOT NULL DEFAULT ''` and `endpoints TEXT NOT NULL DEFAULT '[]'`.

```bash
# Check backfill — existing nodes should have endpoints JSON
adb -s $DEVICE shell run-as com.vela.app \
  sqlite3 /data/data/com.vela.app/databases/vela_database \
  "SELECT id, machine_id, endpoints FROM ssh_nodes LIMIT 5"
```

Expected: existing amplifierd nodes show `endpoints` as a JSON array like `[{"type":"tailscale","url":"http://..."},{"type":"direct","url":"http://..."}]`. SSH-only nodes show `[]`.

**Step 4: Commit**

```bash
# Include the auto-generated Room schema file
git add \
  app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt \
  app/src/main/kotlin/com/vela/app/di/AppModule.kt \
  app/schemas/com.vela.app.data.db.VelaDatabase/18.json
git commit -m "feat(android): DB migration v17→v18 — machine_id + endpoints columns"
```

---

## Phase 1 complete ✓

After all 10 tasks pass:

| Check | How to verify |
|---|---|
| amplifierd `/health` has `machine_id` | `curl -s http://10.0.0.143:8410/health \| python3 -m json.tool` |
| mDNS advertising active | `dns-sd -B _amplifierd._tcp local.` shows the service |
| Android builds cleanly | `./gradlew assembleDebug -x test` → BUILD SUCCESSFUL |
| DB schema has new columns | `sqlite3 ... ".schema ssh_nodes"` |
| Existing nodes backfilled | `sqlite3 ... "SELECT endpoints FROM ssh_nodes"` shows JSON |

**Next:** Phase 2 plan adds `EndpointResolver`, `MdnsDiscoveryService`, `ConnectivityPoller`, and wires them into `HomeViewModel`, `SessionStreamingManagerImpl`, and `VelaApplication`.
