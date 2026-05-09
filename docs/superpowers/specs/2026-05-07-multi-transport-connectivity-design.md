# Multi-Transport Node Connectivity Design

**Date:** 2026-05-07  
**Status:** Approved  
**Scope:** Vela Android app + amplifierd daemon

---

## Problem

A single physical Mac running amplifierd is stored as one hardcoded IP in the database. The right network path to reach it changes depending on where the user is:

- **Same WiFi** → LAN IP (`192.168.1.50`) or auto-discovered via mDNS
- **Different network** → Tailscale IP (`100.x.x.x`)
- **Future** → USB, Wi-Fi Direct, etc.

There are two additional bugs in the current implementation:

1. `findReachableUrl()` correctly discovers the winning URL but never propagates it to session clients — they always use `node.url` (the static LAN IP from bootstrap). If Tailscale is the only live path, sessions fail even though the home screen shows the node as online.
2. Health polling is a fixed 60-second loop regardless of whether the user is looking at the screen.

---

## Goals

- One logical node (`SshNode`) reachable via multiple transports
- Auto-discover the Mac on the local network without knowing its IP (mDNS)
- Connect via Tailscale when on a different network
- Stable node identity that survives reboots and IP changes
- Fix active URL propagation so sessions use the actually-reachable URL
- Smarter polling: immediate on page visit, exponential backoff to 60s max
- Home page only shows explicitly added nodes — mDNS is silent transport resolution, not discovery UI

---

## Design

### 1. Node Identity — `machine_id`

Every amplifierd node gets a stable `machine_id` derived from hardware at startup:

```
macOS  → IOPlatformUUID via ioreg
         e.g. 65E872B0-3343-5255-8409-8C2C13974937

Linux  → /sys/class/dmi/id/product_uuid   (firmware-bound, may need root)
       → /etc/machine-id                  (fallback — persists across reboots)
       → generate + persist to ~/.amplifier/machine-id  (last resort)
```

The `machine_id` is read once at startup, cached in memory, and returned from `/health`:

```json
{
    "status": "healthy",
    "version": "0.1.0",
    "machine_id": "65E872B0-3343-5255-8409-8C2C13974937",
    "uptime_seconds": 1888.81,
    "active_sessions": 0,
    "rust_engine": false
}
```

**Identity rule:** Any endpoint that responds to `/health` with a known `machine_id` using the stored token is confirmed to be that node, regardless of transport.

---

### 2. Data Model — `NodeEndpoint`

Replace `url` + `tailscaleUrl` + `hosts[]` (for HTTP) with a typed endpoint list:

```kotlin
sealed class NodeEndpoint {
    // Explicit IP/URL — from SSH bootstrap, Tailscale, or manual entry
    data class Direct(val url: String) : NodeEndpoint()

    // Tailscale IP — tried when not on the same LAN
    data class Tailscale(val url: String) : NodeEndpoint()

    // mDNS service name — no stored IP, resolved live
    data class Mdns(val serviceName: String) : NodeEndpoint()
}
```

`SshNode` gains two fields and loses the HTTP-specific URL fields:

```kotlin
data class SshNode(
    val id: String,
    val label: String,
    val machineId: String = "",                    // canonical identity from /health
    val endpoints: List<NodeEndpoint> = emptyList(), // ordered: Mdns → Tailscale → Direct
    val token: String = "",
    // SSH bootstrap fields (unchanged)
    val port: Int = 22,
    val username: String = "",
    val hosts: List<String> = emptyList(),
    val bootstrapStatus: BootstrapStatus,
    val workspaceDir: String = "~",
    val addedAt: Long,
    val type: NodeType,
)
```

SSH bootstrap populates `endpoints` with `Direct(url)` and optionally `Tailscale(tailscaleUrl)`. When `MdnsDiscoveryService` matches a discovered service to a saved node, it persists `Mdns(serviceName)` to that node's endpoints so future app starts know to expect mDNS for this node. The **resolved IP** is always ephemeral — held in memory by `MdnsDiscoveryService`, never stored. Manual additional IPs can be added as `Direct` entries.

---

### 3. mDNS — amplifierd Advertising

amplifierd registers a mDNS service at startup:

```
Service type:  _amplifierd._tcp.local.
Service name:  <node-label>._amplifierd._tcp.local.
Port:          8410
TXT records:   machine_id=65E872B0-3343-5255-8409-8C2C13974937
               version=0.1.0
```

- Uses the OS mDNS stack (Bonjour on macOS, Avahi on Linux)
- No stored IP — the OS resolves the hostname dynamically
- Deregisters cleanly on shutdown

---

### 4. mDNS — Android Discovery (`MdnsDiscoveryService`)

A new `@Singleton` service using Android `NsdManager`:

```kotlin
class MdnsDiscoveryService @Inject constructor(
    val nsdManager: NsdManager,
    val registry: SshNodeRegistry,
) {
    // Keyed by node ID → resolved "http://host:port" URL
    private val resolvedUrls = ConcurrentHashMap<String, String>()

    fun start() {
        nsdManager.discoverServices("_amplifierd._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        nsdManager.stopServiceDiscovery(listener)
    }

    fun resolvedUrl(serviceName: String): String? {
        // Look up by service name → node ID → resolved URL
        val nodeId = registry.cache
            .find { node -> node.endpoints.any { it is Mdns && it.serviceName == serviceName } }
            ?.id
        return nodeId?.let { resolvedUrls[it] }
    }

    // On service found:
    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val machineId = resolved.attributes["machine_id"]?.toString() ?: return
                val node = registry.cache.find { it.machineId == machineId } ?: return
                // Known node — update resolved URL in memory
                resolvedUrls[node.id] = "http://${resolved.host.hostAddress}:${resolved.port}"
                // Unknown node — ignore, do not surface in UI
            }
        })
    }
}
```

**Key constraint:** If a discovered service's `machine_id` doesn't match any saved node, it is silently ignored. The home page only shows nodes the user has explicitly added.

`MdnsDiscoveryService` starts/stops with app foreground state via `ProcessLifecycleOwner`:

```kotlin
class VelaApplication : Application() {
    override fun onCreate() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mdnsDiscovery.start()
            override fun onStop(owner: LifecycleOwner) = mdnsDiscovery.stop()
        })
    }
}
```

---

### 5. Endpoint Resolution — `EndpointResolver`

Replaces `findReachableUrl()`. Returns a ready-to-use `AmplifierdClient` pointed at the winning endpoint. Callers never interact with raw URLs.

```kotlin
class EndpointResolver @Inject constructor(
    val mdnsDiscovery: MdnsDiscoveryService,
) {
    // Priority order: Mdns (lowest latency on LAN) → Tailscale → Direct
    suspend fun resolve(node: SshNode): AmplifierdClient? {
        for (endpoint in prioritized(node.endpoints)) {
            val url = toUrl(endpoint) ?: continue
            val client = AmplifierdClient(url, node.token)
            if (client.health()) return client
        }
        return null
    }

    private fun toUrl(endpoint: NodeEndpoint): String? = when (endpoint) {
        is NodeEndpoint.Direct    -> endpoint.url
        is NodeEndpoint.Tailscale -> endpoint.url
        is NodeEndpoint.Mdns      -> mdnsDiscovery.resolvedUrl(endpoint.serviceName)
    }

    private fun prioritized(endpoints: List<NodeEndpoint>): List<NodeEndpoint> =
        endpoints.sortedBy {
            when (it) {
                is NodeEndpoint.Mdns      -> 0   // LAN is fastest
                is NodeEndpoint.Tailscale -> 1
                is NodeEndpoint.Direct    -> 2
            }
        }
}
```

**Bug fix:** `SessionStreamingManagerImpl`, `HomeViewModel`, and all other callers go through `EndpointResolver.resolve()`. The static `node.url` is never used directly for HTTP connections.

`AmplifierdRepository.clientForNode()` and `streamClientForNode()` are updated to call `resolver.resolve()` and build clients from the returned URL.

---

### 6. Polling — `ConnectivityPoller`

Replaces the fixed 60-second loop in `HomeViewModel`:

```kotlin
class ConnectivityPoller @Inject constructor(
    val resolver: EndpointResolver,
    val registry: SshNodeRegistry,
    val connectivity: MutableStateFlow<Map<String, NodeConnectivity>>,
) {
    private var job: Job? = null
    private var currentInterval = INITIAL_INTERVAL

    // Called from HomeFragment.onResume()
    fun onPageVisible() {
        job?.cancel()
        currentInterval = INITIAL_INTERVAL
        job = scope.launch { pollLoop() }
    }

    // Called from HomeFragment.onPause()
    fun onPageHidden() {
        job?.cancel()
        job = null
    }

    private suspend fun pollLoop() {
        while (true) {
            checkAllNodes()
            delay(currentInterval)
            currentInterval = (currentInterval * 2).coerceAtMost(MAX_INTERVAL)
        }
    }

    private suspend fun checkAllNodes() {
        registry.cache
            .filter { it.type == NodeType.AMPLIFIERD }
            .map { node ->
                async {
                    connectivity.update { it + (node.id to NodeConnectivity.Checking) }
                    val client = resolver.resolve(node)
                    val state = if (client != null)
                        NodeConnectivity.Reachable(client.baseUrl)
                    else
                        NodeConnectivity.Unreachable
                    connectivity.update { it + (node.id to state) }
                }
            }
            .awaitAll()
    }

    companion object {
        val INITIAL_INTERVAL = 5.seconds
        val MAX_INTERVAL = 60.seconds
    }
}
```

**Backoff sequence on page visit:** immediate → 5s → 10s → 20s → 40s → 60s → 60s → ...

When user leaves and returns — resets to immediate.

All nodes are checked in parallel within each poll tick.

---

### 7. DB Migration (v18)

```sql
-- Add machine_id column
ALTER TABLE ssh_nodes ADD COLUMN machine_id TEXT NOT NULL DEFAULT '';

-- Add endpoints as JSON column
-- Format: [{"type":"direct","url":"http://..."},{"type":"tailscale","url":"http://..."},
--           {"type":"mdns","serviceName":"label._amplifierd._tcp.local."}]
ALTER TABLE ssh_nodes ADD COLUMN endpoints TEXT NOT NULL DEFAULT '[]';
```

**Migration logic (Kotlin):**
```kotlin
// Migration 17 → 18
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN machine_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN endpoints TEXT NOT NULL DEFAULT '[]'")

        // Backfill endpoints from existing url + tailscale_url columns
        val cursor = db.query("SELECT id, url, tailscale_url FROM ssh_nodes")
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val url = cursor.getString(1)
            val tailscaleUrl = cursor.getString(2)
            val endpoints = buildList {
                if (tailscaleUrl.isNotBlank()) add("""{"type":"tailscale","url":"$tailscaleUrl"}""")
                if (url.isNotBlank()) add("""{"type":"direct","url":"$url"}""")
            }
            val json = "[${endpoints.joinToString(",")}]"
            db.execSQL("UPDATE ssh_nodes SET endpoints = ? WHERE id = ?", arrayOf(json, id))
        }
        cursor.close()
        // Old url + tailscale_url columns are kept but no longer written going forward
    }
}
```

---

### 8. amplifierd Changes (summary)

Two small additions to amplifierd — no new endpoints, no behavior changes to existing functionality:

| Change | Detail |
|---|---|
| `machine_id` in `/health` | Read at startup via platform fallback chain, cached in memory, returned in every `/health` response |
| mDNS advertising | On startup: register `_amplifierd._tcp.local.` with port + `machine_id` TXT record. On shutdown: deregister. |

**Platform fallback chain (Python pseudocode):**
```python
def get_machine_id() -> str:
    if sys.platform == "darwin":
        return _read_ioreg_uuid()             # IOPlatformUUID
    dmi_path = "/sys/class/dmi/id/product_uuid"
    if os.path.exists(dmi_path):
        return open(dmi_path).read().strip()  # firmware-bound
    machine_id_path = "/etc/machine-id"
    if os.path.exists(machine_id_path):
        return open(machine_id_path).read().strip()
    return _generate_and_persist()            # ~/.amplifier/machine-id
```

---

## What Changes, Summarized

| Layer | Change |
|---|---|
| **amplifierd** | `machine_id` in `/health`; mDNS service advertising |
| **`SshNode`** | + `machineId: String`, `endpoints: List<NodeEndpoint>`; HTTP URL fields deprecated |
| **`NodeEndpoint`** | New sealed class: `Direct`, `Tailscale`, `Mdns` |
| **`EndpointResolver`** | New — replaces `findReachableUrl()`, returns ready `AmplifierdClient` |
| **`MdnsDiscoveryService`** | New — NSD listener, resolves live IPs for saved nodes only |
| **`ConnectivityPoller`** | New — replaces 60s loop, backoff with page-visit reset |
| **`HomeViewModel`** | Uses `ConnectivityPoller`; polling driven by Fragment lifecycle |
| **`SessionStreamingManagerImpl`** | Uses `EndpointResolver.resolve()` instead of `node.url` |
| **DB** | Migration v18: `machine_id` + `endpoints` JSON columns |
| **Bug fix** | Active URL propagated through resolver to all session clients |

## What Does NOT Change

- SSH bootstrap flow — still the primary way to configure a node
- Auth token — still required for all HTTP connections
- Home page behavior — only shows explicitly added nodes; mDNS discovery is silent
- `AmplifierdClient` / `AmplifierdStreamClient` internals — same HTTP/SSE implementation
- Session creation, streaming, steer — unaffected

---

## Future Transports

Adding a new transport (USB, Wi-Fi Direct) requires:
1. A new `NodeEndpoint` subclass
2. A `toUrl()` branch in `EndpointResolver`
3. A discovery mechanism that populates `resolvedUrls`

No changes to `SshNode`, `AmplifierdClient`, or the session layer.
