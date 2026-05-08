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

    // ── NSD listener ───────────────────────────────────────────────────────

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
