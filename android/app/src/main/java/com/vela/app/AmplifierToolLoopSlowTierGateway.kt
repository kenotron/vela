package com.vela.app

import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.voice.handoff.SlowTierEvent
import com.vela.voice.handoff.SlowTierGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Concrete [SlowTierGateway] bridging [com.vela.voice.handoff.TierCoordinator] to the real
 * slow tier (`vela-agentd`) via [AmplifierToolLoopClient] (issue #61).
 *
 * Lives in `android/app` rather than `android/voice` because `:voice` does not (and per its
 * own module boundary should not) depend on `:host-tools` -- `:app` already depends on both,
 * so this is the natural composition point for the bridge (see `VelaAppContainer.kt`).
 *
 * ## Named residual: no true progress narration
 *
 * [AmplifierToolLoopClient.runTurn] is a single suspend call with no incremental/streaming
 * progress API -- it only returns a [AmplifierToolLoopClient.TurnResult] once the whole tool
 * loop has converged (or throws). Per this lane's goal (and #23's narration invariant --
 * narration must only ever derive from *real* slow-tier events, never synthetic filler),
 * this gateway does **not** fabricate [SlowTierEvent.Progress] events. It emits exactly one
 * terminal event: [SlowTierEvent.Completed] on success, or [SlowTierEvent.Failed] on any
 * exception. Real progress narration remains blocked on `AmplifierToolLoopClient` (or a
 * successor) exposing a streaming/incremental result API.
 */
class AmplifierToolLoopSlowTierGateway(
    private val client: AmplifierToolLoopClient,
) : SlowTierGateway {
    override fun dispatch(utterance: String): Flow<SlowTierEvent> = flow {
        try {
            val result = client.runTurn(userMessage = utterance)
            emit(SlowTierEvent.Completed(result.finalContent))
        } catch (e: Exception) {
            emit(SlowTierEvent.Failed(e.message ?: "slow-tier request failed"))
        }
    }
}
