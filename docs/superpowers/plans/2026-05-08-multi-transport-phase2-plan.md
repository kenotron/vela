# Multi-Transport Connectivity — Phase 2: Resolution Layer + Wiring

> **For execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Implement `EndpointResolver`, `MdnsDiscoveryService`, `ConnectivityPoller`, and wire them through `AmplifierdRepository`, `HomeViewModel`, `SessionStreamingManagerImpl`, and `VelaApplication`. Fixes the active-URL propagation bug and completes mDNS connectivity.

**Architecture:** `EndpointResolver` probes each typed endpoint in priority order (mDNS → Tailscale → Direct) and returns the first live `AmplifierdClient`. `MdnsDiscoveryService` listens for `_amplifierd._tcp` advertisements using Android NSD, resolves IPs in memory, and silently ignores unknown nodes. `ConnectivityPoller` drives the home-screen health loop with exponential backoff, replacing the old fixed-60s timer.

**Tech Stack:** Kotlin, Android NSD (`NsdManager`), Coroutines/Flow, Hilt DI, Room (already wired — Phase 1 added migration v18)

**Depends on:** Phase 1 plan — must be merged before starting Phase 2. Phase 1 delivers: `NodeEndpoint.kt` (sealed class `Direct`/`Tailscale`/`Mdns`), updated `SshNode.kt` (+ `machineId`, `endpoints`), updated `SshNodeEntity.kt` (+ `machine_id`, `endpoints` columns), `MIGRATION_17_18` in `VelaDatabase.kt`, updated `SshNodeRegistry.toDomain()/toEntity()` mapping for new fields, and an updated `promoteToAmplifierd` DAO call that writes the initial `endpoints` JSON.

**No TDD.** Verify via build + install + logcat + app checks.

---

## Build + Install Commands (reuse across every task)

```bash
DEVICE=$(./scripts/vela-device)
./gradlew assembleDebug -x test 2>&1 | tail -5
adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $DEVICE shell am start --user 0 -n com.vela.app/.MainActivity
```

Expected build output ends with:
```
BUILD SUCCESSFUL in Xs
```

---

## Task 1: Expose `baseUrl` on `AmplifierdClient` + add `healthWithDetails()`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdClient.kt`
- Modify: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdModels.kt`

**Step 1: Implement**

In `AmplifierdModels.kt`, add a new data class at the bottom of the file:

```kotlin
data class HealthResponse(
    val status: String,
    val machineId: String,
    val version: String = "",
)
```

In `AmplifierdClient.kt` line 21, change `private val baseUrl` to public:

```kotlin
// Before:
class AmplifierdClient(private val baseUrl: String, private val token: String) {

// After:
class AmplifierdClient(val baseUrl: String, private val token: String) {
```

In `AmplifierdClient.kt`, add `healthWithDetails()` immediately after the existing `health()` method (after line 543):

```kotlin
/**
 * GET /health → parsed [HealthResponse] including machine_id, or null on any error.
 */
suspend fun healthWithDetails(): HealthResponse? = try {
    withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/health")
            .header("x-amplifier-token", token)
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val body = res.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            HealthResponse(
                status    = json.optString("status", ""),
                machineId = json.optString("machine_id", ""),
                version   = json.optString("version", ""),
            )
        }
    }
} catch (_: Exception) { null }
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

No visible behavior change yet. Confirm it builds.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdClient.kt \
        app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdModels.kt
git commit -m "feat(connectivity): expose baseUrl + add healthWithDetails() to AmplifierdClient"
```

---

## Task 2: Add `updateMachineId` + `updateEndpoints` to `SshNodeDao`, extend `SshNodeRegistry`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt`

**Step 1: Implement**

In `SshNodeDao.kt`, add two new query methods inside the `interface SshNodeDao` body, after the existing `updateConnection` method:

```kotlin
/** Write the machine_id discovered from /health. */
@Query("UPDATE ssh_nodes SET machine_id = :machineId WHERE id = :id")
suspend fun updateMachineId(id: String, machineId: String)

/** Overwrite the entire endpoints JSON column. */
@Query("UPDATE ssh_nodes SET endpoints = :endpoints WHERE id = :id")
suspend fun updateEndpoints(id: String, endpoints: String)
```

In `SshNodeRegistry.kt`, add the following methods after `updateBootstrapStatus`:

```kotlin
/** Persist a newly-discovered machine_id (read from /health after bootstrap). */
suspend fun updateMachineId(nodeId: String, machineId: String) {
    dao.updateMachineId(nodeId, machineId)
}

/**
 * Appends [endpoint] to [nodeId]'s endpoint list if not already present.
 * Used by [MdnsDiscoveryService] to persist a newly-discovered mDNS service name.
 */
suspend fun addEndpoint(nodeId: String, endpoint: NodeEndpoint) {
    val node = cache.find { it.id == nodeId } ?: return
    // Idempotent — skip if an equivalent endpoint already exists
    val alreadyHas = node.endpoints.any { existing ->
        when {
            endpoint is NodeEndpoint.Mdns && existing is NodeEndpoint.Mdns ->
                existing.serviceName == endpoint.serviceName
            endpoint is NodeEndpoint.Direct && existing is NodeEndpoint.Direct ->
                existing.url == endpoint.url
            endpoint is NodeEndpoint.Tailscale && existing is NodeEndpoint.Tailscale ->
                existing.url == endpoint.url
            else -> false
        }
    }
    if (alreadyHas) return
    val updatedJson = serializeEndpoints(node.endpoints + endpoint)
    dao.updateEndpoints(nodeId, updatedJson)
}

/** Serialize [NodeEndpoint] list to the JSON format used in the DB endpoints column. */
private fun serializeEndpoints(endpoints: List<NodeEndpoint>): String {
    val arr = org.json.JSONArray()
    endpoints.forEach { ep ->
        val obj = org.json.JSONObject()
        when (ep) {
            is NodeEndpoint.Direct    -> { obj.put("type", "direct");    obj.put("url", ep.url) }
            is NodeEndpoint.Tailscale -> { obj.put("type", "tailscale"); obj.put("url", ep.url) }
            is NodeEndpoint.Mdns      -> { obj.put("type", "mdns");      obj.put("serviceName", ep.serviceName) }
        }
        arr.put(obj)
    }
    return arr.toString()
}
```

Add the `NodeEndpoint` import to `SshNodeRegistry.kt` if Phase 1 hasn't already:

```kotlin
import com.vela.app.ssh.NodeEndpoint
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

No visible behavior change yet. Build success is the check.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt \
        app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt
git commit -m "feat(connectivity): add updateMachineId, updateEndpoints, addEndpoint persistence helpers"
```

---

## Task 3: Create `EndpointResolver.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/amplifierd/EndpointResolver.kt`

**Step 1: Implement**

Create the file with this complete content:

```kotlin
package com.vela.app.amplifierd

import android.util.Log
import com.vela.app.ssh.MdnsDiscoveryService
import com.vela.app.ssh.NodeEndpoint
import com.vela.app.ssh.SshNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the best reachable [AmplifierdClient] for a node by probing each
 * [NodeEndpoint] in priority order: Mdns (LAN fastest) → Tailscale → Direct.
 *
 * Each candidate URL is probed with a real GET /health before being accepted.
 * Returns null if all endpoints fail or the node has no endpoints.
 *
 * Callers receive a client already pointed at the winning URL — no raw URLs escape.
 */
@Singleton
class EndpointResolver @Inject constructor(
    private val mdnsDiscovery: MdnsDiscoveryService,
) {
    /**
     * Returns the first [AmplifierdClient] whose /health responds with 200,
     * probing in priority order. Returns null if no endpoint is reachable.
     */
    suspend fun resolve(node: SshNode): AmplifierdClient? {
        if (node.endpoints.isEmpty()) {
            // Legacy node (no endpoints yet — pre-migration or pre-mDNS-discovery).
            // Fall back to the stored url field so existing nodes still work.
            val fallbackUrl = node.url.takeIf { it.isNotBlank() } ?: return null
            return try {
                val client = AmplifierdClient(fallbackUrl, node.token)
                if (client.health()) client else null
            } catch (_: Exception) { null }
        }
        for (endpoint in prioritized(node.endpoints)) {
            val url = toUrl(endpoint) ?: continue      // mDNS not resolved yet → skip
            val client = try {
                AmplifierdClient(url, node.token)
            } catch (_: Exception) { continue }
            val reachable = try { client.health() } catch (_: Exception) { false }
            if (reachable) {
                Log.d(TAG, "resolve '${node.label}': ${endpoint::class.simpleName} → $url")
                return client
            }
        }
        Log.w(TAG, "resolve '${node.label}': all endpoints unreachable")
        return null
    }

    /** Converts an endpoint to its HTTP URL, or null if not yet resolvable (mDNS pending). */
    fun toUrl(endpoint: NodeEndpoint): String? = when (endpoint) {
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

    companion object {
        private const val TAG = "EndpointResolver"
    }
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

> **Note:** `MdnsDiscoveryService` doesn't exist yet — the build will fail with an unresolved reference. That's expected. Proceed immediately to Task 4.

**Step 4: Commit** (after Task 4 compiles cleanly)

Hold the commit until Task 4 is done.

---

## Task 4: Create `MdnsDiscoveryService.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ssh/MdnsDiscoveryService.kt`

**Step 1: Implement**

Create the file with this complete content:

```kotlin
package com.vela.app.ssh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton service that listens for amplifierd mDNS advertisements on the LAN.
 *
 * **Silent transport resolver, not discovery UI.**
 * Discovered services are matched by `machine_id` TXT record against the saved-node
 * registry. Unrecognised services are silently ignored — they never surface in the UI.
 *
 * Lifecycle: start on app-foreground, stop on app-background (wired from [VelaApplication]).
 * Resolved IPs are held in memory only — they are re-discovered fresh on each app start.
 * The mDNS service *name* (FQDN) is persisted to the DB via [SshNodeRegistry.addEndpoint].
 */
@Singleton
class MdnsDiscoveryService @Inject constructor(
    private val nsdManager: NsdManager,
    private val registry: SshNodeRegistry,
    @ApplicationContext private val context: Context,
) {
    // Keyed by node ID → live-resolved "http://host:port" URL (ephemeral, not persisted)
    private val resolvedUrls = ConcurrentHashMap<String, String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Private scope for background DB writes (addEndpoint is suspend)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start NSD discovery. No-op if already running. */
    fun start() {
        if (discoveryListener != null) return
        discoveryListener = createDiscoveryListener()
        nsdManager.discoverServices(
            "_amplifierd._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener!!,
        )
        Log.d(TAG, "start: NSD discovery registered")
    }

    /** Stop NSD discovery. No-op if not running. */
    fun stop() {
        val listener = discoveryListener ?: return
        try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { /* already stopped */ }
        discoveryListener = null
        Log.d(TAG, "stop: NSD discovery unregistered")
    }

    /**
     * Returns the live-resolved HTTP URL for the given fully-qualified mDNS service name,
     * e.g. `"ken's mac._amplifierd._tcp.local."`. Returns null if not yet resolved.
     */
    fun resolvedUrl(serviceName: String): String? {
        val nodeId = registry.cache
            .find { node ->
                node.endpoints.any { it is NodeEndpoint.Mdns && it.serviceName == serviceName }
            }
            ?.id
        return nodeId?.let { resolvedUrls[it] }
    }

    // ── NSD listener ──────────────────────────────────────────────────────────

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed: $errorCode — discovery not active")
            discoveryListener = null
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "onDiscoveryStarted: $serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "onDiscoveryStopped: $serviceType")
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            Log.d(TAG, "onServiceFound: ${info.serviceName}")
            triggerResolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "onServiceLost: ${info.serviceName}")
            // Evict the cached URL so the poller falls back to Tailscale/Direct
            val fullName = "${info.serviceName}._amplifierd._tcp.local."
            val nodeId = registry.cache
                .find { node ->
                    node.endpoints.any { it is NodeEndpoint.Mdns && it.serviceName == fullName }
                }
                ?.id
            if (nodeId != null) resolvedUrls.remove(nodeId)
        }
    }

    /**
     * Ask NsdManager to resolve [info] to an IP address + TXT records.
     * Wrapped in try-catch because only one resolution can be in-flight at a time
     * (FAILURE_ALREADY_ACTIVE is thrown as an exception on some Android versions).
     */
    private fun triggerResolve(info: NsdServiceInfo) {
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "onResolveFailed: ${info.serviceName} code=$errorCode")
                }
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    handleResolved(resolved)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "triggerResolve threw (likely FAILURE_ALREADY_ACTIVE): ${e.message}")
        }
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        // TXT attributes are ByteArray values — decode machine_id
        val machineIdBytes = resolved.attributes["machine_id"] ?: run {
            Log.d(TAG, "handleResolved: no machine_id TXT for '${resolved.serviceName}' — ignoring")
            return
        }
        val machineId = String(machineIdBytes)

        // Only care about nodes the user has explicitly added
        val node = registry.cache.find { it.machineId == machineId } ?: run {
            Log.d(TAG, "handleResolved: machine_id=$machineId not in registry — ignoring")
            return
        }

        val hostAddress = resolved.host?.hostAddress ?: run {
            Log.w(TAG, "handleResolved: null host for '${resolved.serviceName}'")
            return
        }
        val url = "http://$hostAddress:${resolved.port}"
        resolvedUrls[node.id] = url
        Log.d(TAG, "handleResolved: '${node.label}' resolved → $url")

        // Persist the Mdns service name to this node's endpoints if not already stored.
        // The full FQDN format: "<instanceName>._amplifierd._tcp.local."
        val serviceName = "${resolved.serviceName}._amplifierd._tcp.local."
        val alreadyStored = node.endpoints.any {
            it is NodeEndpoint.Mdns && it.serviceName == serviceName
        }
        if (!alreadyStored) {
            scope.launch {
                registry.addEndpoint(node.id, NodeEndpoint.Mdns(serviceName))
                Log.d(TAG, "handleResolved: persisted Mdns endpoint for '${node.label}'")
            }
        }
    }

    companion object {
        private const val TAG = "MdnsDiscovery"
    }
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (Tasks 3 and 4 should now compile together)

**Step 3: App verify**

No visible behavior yet. Build success is the check.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/amplifierd/EndpointResolver.kt \
        app/src/main/kotlin/com/vela/app/ssh/MdnsDiscoveryService.kt
git commit -m "feat(connectivity): add EndpointResolver and MdnsDiscoveryService"
```

---

## Task 5: Create `ConnectivityPoller.kt`

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ssh/ConnectivityPoller.kt`

**Step 1: Implement**

Create the file with this complete content:

```kotlin
package com.vela.app.ssh

import android.util.Log
import com.vela.app.amplifierd.EndpointResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Singleton that drives periodic health checks for all AMPLIFIERD nodes.
 *
 * Backoff sequence on each [onPageVisible]: immediate → 5s → 10s → 20s → 40s → 60s → 60s…
 * Resets to immediate every time the user navigates back to the home screen.
 * All nodes are checked in parallel within each tick.
 *
 * Owns [nodeConnectivity] — the [StateFlow] consumed by [HomeViewModel].
 */
@Singleton
class ConnectivityPoller @Inject constructor(
    private val resolver: EndpointResolver,
    private val registry: SshNodeRegistry,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var currentInterval: Duration = INITIAL_INTERVAL

    private val _nodeConnectivity = MutableStateFlow<Map<String, NodeConnectivity>>(emptyMap())

    /** Live reachability state for each AMPLIFIERD node, keyed by node ID. */
    val nodeConnectivity: StateFlow<Map<String, NodeConnectivity>> = _nodeConnectivity.asStateFlow()

    /**
     * Call when the home screen becomes visible (ON_RESUME).
     * Cancels any running poll, resets backoff to immediate, starts a fresh loop.
     */
    fun onPageVisible() {
        pollJob?.cancel()
        currentInterval = INITIAL_INTERVAL
        pollJob = scope.launch { pollLoop() }
        Log.d(TAG, "onPageVisible: poll started")
    }

    /**
     * Call when the home screen is no longer visible (ON_PAUSE or navigation away).
     * Cancels the poll loop to avoid unnecessary network traffic.
     */
    fun onPageHidden() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "onPageHidden: poll stopped")
    }

    /**
     * Trigger a single immediate check for all nodes without disrupting the scheduled
     * poll cycle. Used by [HomeViewModel] when a new node is added while on the home screen.
     */
    fun checkNow() {
        scope.launch { checkAllNodes() }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun pollLoop() {
        while (true) {
            checkAllNodes()
            Log.d(TAG, "pollLoop: next check in $currentInterval")
            delay(currentInterval)
            currentInterval = (currentInterval * 2).coerceAtMost(MAX_INTERVAL)
        }
    }

    private suspend fun checkAllNodes() = coroutineScope {
        val amplifierdNodes = registry.cache.filter { it.type == NodeType.AMPLIFIERD }
        if (amplifierdNodes.isEmpty()) return@coroutineScope
        amplifierdNodes
            .map { node ->
                async {
                    _nodeConnectivity.update { it + (node.id to NodeConnectivity.Checking) }
                    val client = resolver.resolve(node)
                    val state = if (client != null)
                        NodeConnectivity.Reachable(client.baseUrl)
                    else
                        NodeConnectivity.Unreachable
                    _nodeConnectivity.update { it + (node.id to state) }
                    Log.d(TAG, "checkNode '${node.label}': $state")
                }
            }
            .awaitAll()
    }

    companion object {
        private val INITIAL_INTERVAL = 5.seconds
        private val MAX_INTERVAL = 60.seconds
        private const val TAG = "ConnectivityPoller"
    }
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

No visible behavior change yet. Build success is the check.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ssh/ConnectivityPoller.kt
git commit -m "feat(connectivity): add ConnectivityPoller with exponential backoff"
```

---

## Task 6: Wire DI in `AppModule.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

**Step 1: Implement**

Make three changes to `AppModule.kt`:

**Change 1** — Add `NsdManager` import at the top with the other imports:

```kotlin
import android.net.nsd.NsdManager
```

**Change 2** — Add `provideNsdManager` after `provideSshKeyManager`:

```kotlin
@Provides @Singleton
fun provideNsdManager(@ApplicationContext ctx: Context): NsdManager =
    ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
```

**Change 3** — Update `provideAmplifierdRepository` to inject `EndpointResolver`:

```kotlin
// Before:
@Provides @Singleton
fun provideAmplifierdRepository(registry: SshNodeRegistry): AmplifierdRepository =
    AmplifierdRepository(registry)

// After:
@Provides @Singleton
fun provideAmplifierdRepository(
    registry: SshNodeRegistry,
    resolver: EndpointResolver,
): AmplifierdRepository = AmplifierdRepository(registry, resolver)
```

Add the import for `EndpointResolver`:

```kotlin
import com.vela.app.amplifierd.EndpointResolver
```

**Change 4** — Add `MIGRATION_17_18` to the Room migration list (Phase 1 defines this in `VelaDatabase.kt`):

```kotlin
// Before (last migration):
.addMigrations(MIGRATION_1_2, ..., MIGRATION_16_17)

// After:
.addMigrations(MIGRATION_1_2, ..., MIGRATION_16_17, MIGRATION_17_18)
```

> **Note:** `EndpointResolver`, `MdnsDiscoveryService`, and `ConnectivityPoller` are all `@Singleton` with `@Inject constructor`, so Hilt injects them automatically. No explicit `@Provides` needed for those three.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

> If Hilt complains about `AmplifierdRepository` constructor mismatch, you may also need to update the `AmplifierdRepository` constructor signature first (Task 7). Build Task 7 next and return here to verify.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/di/AppModule.kt
git commit -m "feat(connectivity): wire NsdManager, EndpointResolver in DI; register MIGRATION_17_18"
```

---

## Task 7: Update `AmplifierdRepository.kt` — use resolver, make `clientForNode` suspend

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdRepository.kt`

**Step 1: Implement**

Replace the entire file with:

```kotlin
package com.vela.app.amplifierd

import android.util.Log
import com.vela.app.ssh.NodeEndpoint
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that vends [AmplifierdClient] and [AmplifierdStreamClient] instances.
 *
 * All methods that return clients now resolve the best reachable endpoint first
 * (via [EndpointResolver]) and run a /health probe before returning.
 * Returns null if the node is unreachable or not of type [NodeType.AMPLIFIERD].
 */
@Singleton
class AmplifierdRepository @Inject constructor(
    private val registry: SshNodeRegistry,
    private val resolver: EndpointResolver,
) {
    /**
     * Resolves the best reachable endpoint for [node] and returns an [AmplifierdClient]
     * already pointed at the winning URL. Performs a /health probe on each candidate.
     *
     * This is a suspend function — call it from a coroutine (all existing callers already do).
     */
    suspend fun clientForNode(node: SshNode?): AmplifierdClient? {
        if (node == null) {
            Log.w(TAG, "clientForNode: node is null")
            return null
        }
        if (node.type != NodeType.AMPLIFIERD) {
            Log.w(TAG, "clientForNode: '${node.label}' is ${node.type}, not AMPLIFIERD")
            return null
        }
        return resolver.resolve(node)
    }

    /**
     * Resolves the best reachable endpoint and returns an [AmplifierdStreamClient]
     * pointed at the same URL as the resolved [AmplifierdClient].
     * Both clients share the same base URL — avoids stale-URL bugs in streaming.
     */
    suspend fun streamClientForNode(node: SshNode?): AmplifierdStreamClient? {
        val client = clientForNode(node) ?: return null
        return AmplifierdStreamClient(client.baseUrl, node!!.token)
    }

    companion object {
        private const val TAG = "AmplifierdRepository"
    }
}
```

Note: `candidateUrls()`, `findReachableUrl()`, and the old sync `clientForNode` / `clientFor(nodeId)` are all removed. Callers that used `findReachableUrl` are updated in the next task.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: compile errors for `findReachableUrl` callers. That's expected — fix them in Task 8.

**Step 4: Commit** (hold until Task 8 passes build)

---

## Task 8: Fix `findReachableUrl` callers — update `NodeDetailViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt`

**Step 1: Implement**

In `NodeDetailViewModel.kt` at line 61, replace `findReachableUrl` with `clientForNode`:

```kotlin
// Before (line 61):
_isReachable.value = amplifierd.findReachableUrl(n) != null

// After:
_isReachable.value = amplifierd.clientForNode(n) != null
```

That's the only caller of `findReachableUrl` in `NodeDetailViewModel`. All other `amplifierd.clientForNode(node)` calls in this file are unchanged — they were already in `viewModelScope.launch(Dispatchers.IO) { }` blocks, so the suspend signature is compatible.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (all `findReachableUrl` callers should now be fixed)

If the build shows additional callers of `findReachableUrl` in other files (e.g., `SessionListViewModel`, `SessionDetailViewModel`), apply the same pattern: replace with `amplifierd.clientForNode(node) != null`.

**Step 3: App verify**

Install and open the app. Navigate to a node detail screen. Verify it loads capabilities without errors (check logcat for `AmplifierdRepository` or `EndpointResolver` tags).

```bash
DEVICE=$(./scripts/vela-device)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')
adb -s $DEVICE logcat --pid=$APP_PID | grep -E "(EndpointResolver|AmplifierdRepository)"
```

Expected: `EndpointResolver: resolve 'YourNodeName': Direct → http://...`

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdRepository.kt \
        app/src/main/kotlin/com/vela/app/ui/nodedetail/NodeDetailViewModel.kt
git commit -m "feat(connectivity): AmplifierdRepository uses EndpointResolver; fix findReachableUrl callers"
```

---

## Task 9: Update `HomeViewModel.kt` — delegate to `ConnectivityPoller`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt`

**Step 1: Implement**

Replace the entire file with:

```kotlin
package com.vela.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.ConnectivityPoller
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val poller: ConnectivityPoller,
) : ViewModel() {

    /** Live list of all nodes from the DB. */
    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Live reachability state keyed by node ID.
     * Driven by [ConnectivityPoller] — polling starts/stops with page visibility.
     */
    val nodeConnectivity: StateFlow<Map<String, NodeConnectivity>> = poller.nodeConnectivity

    init {
        // Keep registry in-memory cache fresh.
        // When new AMPLIFIERD nodes appear (e.g. just bootstrapped), trigger an immediate check.
        viewModelScope.launch {
            var lastAmplifierdIds = emptySet<String>()
            nodes.collect { current ->
                registry.updateCache(current)
                val ampIds = current
                    .filter { it.type == NodeType.AMPLIFIERD }
                    .map { it.id }
                    .toSet()
                if (ampIds.any { it !in lastAmplifierdIds }) {
                    poller.checkNow()
                }
                lastAmplifierdIds = ampIds
            }
        }
    }

    /** Call from HomeScreen ON_RESUME. Resets backoff and starts polling immediately. */
    fun onPageVisible() = poller.onPageVisible()

    /** Call from HomeScreen ON_PAUSE. Stops polling to conserve battery. */
    fun onPageHidden() = poller.onPageHidden()

    /** Trigger an immediate recheck of all nodes (e.g. pull-to-refresh). */
    fun refreshAll() = poller.checkNow()
}
```

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

Install and navigate to the home screen. The node status chips should still appear (reachable/unreachable).

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ui/home/HomeViewModel.kt
git commit -m "feat(connectivity): HomeViewModel delegates to ConnectivityPoller; drops 60s fixed loop"
```

---

## Task 10: Update `HomeScreen.kt` — lifecycle effects for page-visible polling

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt`

**Step 1: Implement**

Add the following imports to `HomeScreen.kt` (after the existing imports):

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
```

Inside the `HomeScreen` composable, immediately before the `Scaffold(...)` call, add the lifecycle observer block:

```kotlin
// Wire page-visibility into ConnectivityPoller via the ViewModel.
// ON_RESUME: reset backoff, start polling immediately.
// ON_PAUSE:  stop polling (navigated away or app backgrounded).
val lifecycle = LocalLifecycleOwner.current.lifecycle
DisposableEffect(lifecycle, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> viewModel.onPageVisible()
            Lifecycle.Event.ON_PAUSE  -> viewModel.onPageHidden()
            else                      -> {}
        }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
}
```

Place this block between the `val nodeConnectivity by ...` line and the `Scaffold(` line.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

Install and open the home screen. Check logcat:

```bash
DEVICE=$(./scripts/vela-device)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')
adb -s $DEVICE logcat --pid=$APP_PID | grep ConnectivityPoller
```

Expected:
```
ConnectivityPoller: onPageVisible: poll started
ConnectivityPoller: checkNode 'YourNode': Reachable(activeUrl=http://...)
ConnectivityPoller: pollLoop: next check in 5s
```

Navigate away from home and back. Expected log shows `onPageHidden: poll stopped` then `onPageVisible: poll started` with a reset interval.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ui/home/HomeScreen.kt
git commit -m "feat(connectivity): HomeScreen drives ConnectivityPoller via ON_RESUME/ON_PAUSE lifecycle"
```

---

## Task 11: Update `SessionStreamingManagerImpl.kt` — single resolve, shared URL

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt`

**Context:** The active-URL propagation bug: `clientForNode` and `streamClientForNode` previously made independent resolve calls. If the winning URL differed between calls (race condition or mDNS update), the stream client could use a stale URL. Fix: resolve once via `clientForNode`, build the stream client from `client.baseUrl`.

**Step 1: Implement**

**Change 1** — In `startStreaming` (around line 77–82), replace the two separate client fetches:

```kotlin
// Before:
val client = amplifierd.clientForNode(node)
val streamClient = amplifierd.streamClientForNode(node)
if (client == null || streamClient == null) {
    Log.w(TAG, "startStreaming: could not build clients for node ${node.label}")
    return
}

// After:
val client = amplifierd.clientForNode(node)
if (client == null) {
    Log.w(TAG, "startStreaming: node '${node.label}' unreachable on all endpoints")
    return
}
val streamClient = AmplifierdStreamClient(client.baseUrl, node.token)
```

**Change 2** — In `sendMessage` (around line 154–156), replace the stream client fetch:

```kotlin
// Before:
val streamClient = amplifierd.streamClientForNode(node) ?: return false
val client = amplifierd.clientForNode(node)

// After:
val client = amplifierd.clientForNode(node) ?: return false
val streamClient = AmplifierdStreamClient(client.baseUrl, node.token)
```

Add the import for `AmplifierdStreamClient` if not already present (it's in the same `amplifierd` package, but the file uses `amplifierd.streamClientForNode` currently so the class may not be directly imported):

```kotlin
import com.vela.app.amplifierd.AmplifierdStreamClient
```

No other changes needed in this file — `resumeSession` and `reloadTranscriptAfterCompletion` only use `clientForNode`, which is unchanged.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

Install the app. Start a session with an AMPLIFIERD node. Send a message. Verify it streams successfully (no "could not build clients" in logcat).

```bash
DEVICE=$(./scripts/vela-device)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')
adb -s $DEVICE logcat --pid=$APP_PID | grep "SessionStreamingMgr"
```

Expected: no `"could not build clients"` warnings; streaming completes normally.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/streaming/SessionStreamingManagerImpl.kt
git commit -m "fix(streaming): resolve endpoint once per operation, share baseUrl with stream client"
```

---

## Task 12: Update `NodeBootstrapper.kt` — persist `machine_id` after bootstrap

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt`

**Context:** After `promoteToAmplifierd` (the PROMOTE step), we know the node is live. Read `machine_id` from `/health` and write it to the DB. This enables mDNS matching on the next app start.

**Step 1: Implement**

**Change 1** — Add the import for `AmplifierdClient` at the top of `NodeBootstrapper.kt`:

```kotlin
import com.vela.app.amplifierd.AmplifierdClient
```

**Change 2** — In `bootstrapWithShell`, after the PROMOTE step (after `registry.updateBootstrapStatus(nodeId, BootstrapStatus.RUNNING)` at ~line 367), add:

```kotlin
// Persist machine_id so mDNS can match this node by identity on future app starts.
// Best-effort: failure here does not affect connectivity (mDNS will still work via
// repeated discovery, it just won't persist until the next successful resolve).
try {
    val health = AmplifierdClient(lanUrl, token).healthWithDetails()
    if (health?.machineId?.isNotBlank() == true) {
        registry.updateMachineId(nodeId, health.machineId)
        emit(BootstrapEvent.Output("✓ machine_id cached: ${health.machineId.take(8)}…"))
    }
} catch (e: Exception) {
    android.util.Log.w("NodeBootstrapper", "Could not read machine_id from /health: ${e.message}")
}
```

**Change 3** — Update the `throwingDao()` private companion method to include stubs for the two new DAO methods. Find the anonymous `SshNodeDao` object in the companion and add:

```kotlin
override suspend fun updateMachineId(id: String, machineId: String) =
    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
override suspend fun updateEndpoints(id: String, endpoints: String) =
    error("SshNodeDao must not be accessed in NodeBootstrapper helper tests")
```

Add these two stubs inside the anonymous object alongside the other `override suspend fun` stubs.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

This is most easily verified by bootstrapping a fresh node (or noting that the existing bootstrapped node now has a `machine_id` in the DB after a repair/re-bootstrap). Skip if no test device with SSH access is available.

To verify the existing DB state for an already-bootstrapped node:

```bash
DEVICE=$(./scripts/vela-device)
adb -s $DEVICE shell run-as com.vela.app \
  sqlite3 /data/data/com.vela.app/databases/vela_database \
  "SELECT label, machine_id, endpoints FROM ssh_nodes"
```

After a fresh bootstrap with the new code, `machine_id` should be non-empty (e.g., `65E872B0-...`).

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ssh/NodeBootstrapper.kt
git commit -m "feat(connectivity): write machine_id to DB after successful bootstrap"
```

---

## Task 13: Update `VelaApplication.kt` — mDNS foreground lifecycle

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/VelaApplication.kt`

**Context:** `MdnsDiscoveryService` starts when the app enters foreground and stops when it fully backgrounds (not on screen rotation or brief pauses). `ProcessLifecycleOwner` is the right hook — it fires `ON_START`/`ON_STOP` only for true foreground/background transitions.

**Step 1: Implement**

Add imports to `VelaApplication.kt`:

```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vela.app.ssh.MdnsDiscoveryService
```

Add the `MdnsDiscoveryService` injection field alongside the other `@Inject` fields:

```kotlin
@Inject
lateinit var mdnsDiscovery: MdnsDiscoveryService
```

In `onCreate()`, after `miniAppServer.start()`, add the `ProcessLifecycleOwner` observer:

```kotlin
// Start mDNS discovery when app enters foreground; stop when it fully backgrounds.
// ProcessLifecycleOwner fires ON_START/ON_STOP only for true foreground transitions
// (not screen rotations or brief pauses) so this is battery-safe.
ProcessLifecycleOwner.get().lifecycle.addObserver(
    object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { mdnsDiscovery.start() }
        override fun onStop(owner: LifecycleOwner)  { mdnsDiscovery.stop() }
    }
)
```

> **Note:** `ProcessLifecycleOwner` is available from `androidx.lifecycle:lifecycle-process`, which is a transitive dependency of `lifecycle-runtime-ktx 2.8.0`. If the build fails with an unresolved reference, add to `app/build.gradle.kts`:
> ```kotlin
> implementation("androidx.lifecycle:lifecycle-process:2.8.0")
> ```
> and add to `gradle/libs.versions.toml` if using version catalog.

**Step 2: Build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 3: App verify**

Install, open the app, and check logcat:

```bash
DEVICE=$(./scripts/vela-device)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')
adb -s $DEVICE logcat --pid=$APP_PID | grep MdnsDiscovery
```

Expected within a few seconds:
```
MdnsDiscovery: start: NSD discovery registered
MdnsDiscovery: onDiscoveryStarted: _amplifierd._tcp
```

If the Mac is running the updated amplifierd (Phase 1 amplifierd changes), you should also see:
```
MdnsDiscovery: onServiceFound: <node-label>
MdnsDiscovery: handleResolved: 'YourNode' resolved → http://192.168.x.x:8410
```

Background the app (home button), then foreground it:
```
MdnsDiscovery: stop: NSD discovery unregistered
MdnsDiscovery: start: NSD discovery registered
```

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/VelaApplication.kt
git commit -m "feat(connectivity): wire MdnsDiscoveryService to ProcessLifecycleOwner for foreground start/stop"
```

---

## Task 14: End-to-End Verification

All code changes are complete. Run the full verification suite.

**Step 1: Clean build**

```bash
./gradlew assembleDebug -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Install and launch**

```bash
DEVICE=$(./scripts/vela-device)
adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $DEVICE shell am start --user 0 -n com.vela.app/.MainActivity
```

**Step 3: Verify mDNS advertising on the Mac** (requires updated amplifierd with Phase 1 changes)

```bash
dns-sd -B _amplifierd._tcp local
```

Expected within 5 seconds: an entry like:
```
Browsing for _amplifierd._tcp
DATE: ---Thu 08 May 2026---
23:15:01.000  Add        2   5 local _amplifierd._tcp. ken's mac.
```

**Step 4: Verify mDNS discovery in logcat**

```bash
DEVICE=$(./scripts/vela-device)
APP_PID=$(adb -s $DEVICE shell pidof com.vela.app | tr -d ' \r\n')
adb -s $DEVICE logcat --pid=$APP_PID | grep -E "(EndpointResolver|ConnectivityPoller|MdnsDiscovery)"
```

Expected sequence:
```
MdnsDiscovery: start: NSD discovery registered
MdnsDiscovery: onDiscoveryStarted: _amplifierd._tcp
MdnsDiscovery: onServiceFound: ken's mac
MdnsDiscovery: handleResolved: 'ken's mac' resolved → http://192.168.x.x:8410
ConnectivityPoller: onPageVisible: poll started
EndpointResolver: resolve 'ken's mac': Mdns → http://192.168.x.x:8410
ConnectivityPoller: checkNode 'ken's mac': Reachable(activeUrl=http://192.168.x.x:8410)
ConnectivityPoller: pollLoop: next check in 5s
```

**Step 5: Verify machine_id and endpoints persisted in DB**

```bash
DEVICE=$(./scripts/vela-device)
adb -s $DEVICE shell run-as com.vela.app \
  sqlite3 /data/data/com.vela.app/databases/vela_database \
  "SELECT label, machine_id, endpoints FROM ssh_nodes"
```

Expected output (values will differ):
```
ken's mac|65E872B0-3343-5255-8409-8C2C13974937|[{"type":"direct","url":"http://192.168.1.50:8410"},{"type":"tailscale","url":"http://100.x.x.x:8410"},{"type":"mdns","serviceName":"ken's mac._amplifierd._tcp.local."}]
```

**Step 6: Verify exponential backoff**

Stay on the home screen and watch logcat for `ConnectivityPoller`. After the first immediate check, the next check should be at ~5s, then ~10s, ~20s, ~40s, ~60s.

**Step 7: Verify polling stops when backgrounded**

Background the app (home button), then check logcat:
```
ConnectivityPoller: onPageHidden: poll stopped
```

Foreground it:
```
ConnectivityPoller: onPageVisible: poll started
```

**Step 8: Final commit**

```bash
git tag phase2-connectivity-complete
git log --oneline -10
```

All Phase 2 commits should be visible. Squash or leave as-is per team preference.

---

## Troubleshooting

**`findReachableUrl` compile errors after Task 7**

Search for other callers:
```bash
grep -rn "findReachableUrl\|candidateUrls" app/src/main/kotlin/
```
Replace each `findReachableUrl(node)` with `clientForNode(node)` (returns null if unreachable).

**mDNS not discovering the Mac**

Check that both devices are on the same WiFi network (not AP isolation). Verify amplifierd is advertising via `dns-sd -B _amplifierd._tcp local` on the Mac. If amplifierd doesn't yet have Phase 1 changes (mDNS advertising), mDNS discovery will show nothing — fallback to Tailscale/Direct still works.

**`ProcessLifecycleOwner` unresolved reference**

Add to `app/build.gradle.kts`:
```kotlin
implementation("androidx.lifecycle:lifecycle-process:2.8.0")
```

**NSD `onStartDiscoveryFailed` error code 3 (FAILURE_ALREADY_ACTIVE)**

This means `start()` was called while a listener was already registered. The guard `if (discoveryListener != null) return` in `MdnsDiscoveryService.start()` should prevent this. Check that `stop()` is being called correctly on `ON_STOP`.

**Build fails with Hilt `@Provides` error for `ConnectivityPoller` or `EndpointResolver`**

These classes use `@Singleton` + `@Inject constructor` — Hilt auto-generates their binding without an explicit `@Provides`. If Hilt complains about a missing binding for a dependency (e.g., `NsdManager`), verify `provideNsdManager` was added in Task 6.
