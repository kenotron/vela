package com.vela.app.service

import com.vela.core.domain.VoiceTransport
import com.vela.voice.Earcon
import com.vela.voice.EarconMapper
import com.vela.voice.VoiceUiState

/**
 * Notification content for the voice foreground service, decoupled from the
 * real Android Notification/Service APIs so it can be constructed and
 * asserted on in a plain JVM unit test.
 */
public data class VoiceNotificationContent(
    val title: String,
    val text: String,
)

/** Output of a single reducer step: what to show in the notification, and the
 * earcon (if any) that should play as a result of this transition. `earcon`
 * is null when the substate did not actually change (no re-triggering). */
public data class VoiceServiceUiOutput(
    val notification: VoiceNotificationContent,
    val earcon: Earcon?,
)

/**
 * Fine-grained voice session substate, layered on top of
 * [VoiceTransport.TransportState]. The transport only knows about
 * connection-level state (CONNECTED, DISCONNECTED, ...); "thinking" and
 * "speaking" are session-level substates the app tracks on top of a CONNECTED
 * transport (e.g. driven by TranscriptDelta/AudioChunk events), so they are
 * modeled here rather than added to the core-domain interface.
 */
public enum class VoiceSessionSubstate { LISTENING, THINKING, SPEAKING }

/**
 * Pure, plain-JVM-testable reducer mapping [VoiceTransport.TransportState] +
 * [VoiceSessionSubstate] to a (notification content, earcon) pair, satisfying
 * V7 (distinct earcon per state) and V8 (notification always reflects current
 * voice state) at the code level, independent of the real Android Service
 * lifecycle, real Notification APIs, or an emulator.
 *
 * [VoiceForegroundService] is a thin wrapper that feeds real transport state
 * into this reducer and applies the resulting [VoiceServiceUiOutput] to its
 * actual notification and [com.vela.voice.EarconPlayer].
 */
public class VoiceServiceStateReducer {

    private var lastSubstate: VoiceSessionSubstate? = null
    private var lastTransportState: VoiceTransport.TransportState? = null

    /**
     * Compute the notification content and (if this is a genuinely new
     * combined state) the earcon to play for [transportState] + [substate].
     * Only meaningful when [transportState] is CONNECTED; disconnected/
     * connecting/error transport states always take priority in the
     * notification text regardless of [substate], since there is no live
     * voice session substate to speak of in those cases.
     */
    public fun reduce(
        transportState: VoiceTransport.TransportState,
        substate: VoiceSessionSubstate,
    ): VoiceServiceUiOutput {
        val notification = notificationFor(transportState, substate)

        val isNewCombinedState = transportState != lastTransportState || substate != lastSubstate
        val earcon = if (
            transportState == VoiceTransport.TransportState.CONNECTED && isNewCombinedState
        ) {
            EarconMapper.earconFor(substate.toVoiceUiState())
        } else {
            null
        }

        lastTransportState = transportState
        lastSubstate = substate

        return VoiceServiceUiOutput(notification, earcon)
    }

    private fun notificationFor(
        transportState: VoiceTransport.TransportState,
        substate: VoiceSessionSubstate,
    ): VoiceNotificationContent = when (transportState) {
        VoiceTransport.TransportState.DISCONNECTED ->
            VoiceNotificationContent("Vela voice", "Voice session ended")
        VoiceTransport.TransportState.CONNECTING ->
            VoiceNotificationContent("Vela voice", "Connecting…")
        VoiceTransport.TransportState.RECONNECTING ->
            VoiceNotificationContent("Vela voice", "Reconnecting…")
        VoiceTransport.TransportState.ERROR ->
            VoiceNotificationContent("Vela voice", "Voice session error")
        VoiceTransport.TransportState.CONNECTED -> when (substate) {
            VoiceSessionSubstate.LISTENING -> VoiceNotificationContent("Vela voice", "Listening…")
            VoiceSessionSubstate.THINKING -> VoiceNotificationContent("Vela voice", "Thinking…")
            VoiceSessionSubstate.SPEAKING -> VoiceNotificationContent("Vela voice", "Speaking…")
        }
    }

    private fun VoiceSessionSubstate.toVoiceUiState(): VoiceUiState = when (this) {
        VoiceSessionSubstate.LISTENING -> VoiceUiState.LISTENING
        VoiceSessionSubstate.THINKING -> VoiceUiState.THINKING
        VoiceSessionSubstate.SPEAKING -> VoiceUiState.SPEAKING
    }
}
