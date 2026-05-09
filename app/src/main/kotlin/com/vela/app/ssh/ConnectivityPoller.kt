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
 *
 * ## Persistence / no-flash strategy
 * On the first [onPageVisible] call, the StateFlow is seeded from each node's
 * [SshNode.lastKnownReachable] value stored in the DB. This means the home screen
 * renders the last-confirmed state immediately (Ready / Offline) rather than Unknown,
 * eliminating the false "everything looks connected" flash.
 *
 * Nodes with no history (null lastKnownReachable) still start as Unknown → Checking
 * so the tile shows the neutral Checking state instead of incorrectly looking Ready.
 *
 * During re-verification (subsequent polls), nodes that already have a known state are
 * NOT moved to Checking — they keep showing their last result while the probe runs
 * silently, then update only if the result changed.
 */
@Singleton
class ConnectivityPoller @Inject constructor(
    private val resolver: EndpointResolver,
    private val registry: SshNodeRegistry,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var currentInterval: Duration = INITIAL_INTERVAL
    private var seeded = false

    private val _nodeConnectivity = MutableStateFlow<Map<String, NodeConnectivity>>(emptyMap())

    /** Live reachability state for each AMPLIFIERD node, keyed by node ID. */
    val nodeConnectivity: StateFlow<Map<String, NodeConnectivity>> = _nodeConnectivity.asStateFlow()

    /**
     * Call when the home screen becomes visible (ON_RESUME).
     * Seeds from DB on the first call, then cancels any running poll,
     * resets backoff to immediate, and starts a fresh loop.
     */
    fun onPageVisible() {
        if (!seeded) {
            seeded = true
            val lastKnown = registry.lastKnownConnectivity()
            if (lastKnown.isNotEmpty()) {
                _nodeConnectivity.value = lastKnown
                Log.d(TAG, "onPageVisible: seeded ${lastKnown.size} nodes from DB")
            }
        }
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

    // ── Internal ─────────────────────────────────────────────────────────────

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
                    val currentState = _nodeConnectivity.value[node.id]
                    // Show Checking state only when we have no history.
                    // Nodes with a known last state keep their current visual while
                    // the probe runs — the tile updates only if the result changes.
                    if (currentState == null || currentState is NodeConnectivity.Unknown) {
                        _nodeConnectivity.update { it + (node.id to NodeConnectivity.Checking) }
                    }
                    val client = resolver.resolve(node)
                    val newState = if (client != null)
                        NodeConnectivity.Reachable(client.baseUrl)
                    else
                        NodeConnectivity.Unreachable
                    _nodeConnectivity.update { it + (node.id to newState) }
                    // Persist so the next app open starts from this confirmed state
                    registry.updateLastKnownReachable(node.id, client != null)
                    Log.d(TAG, "checkNode '${node.label}': $newState")
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
