package com.vela.voice

/**
 * The three voice-session states that warrant a distinct earcon and (via
 * VoiceServiceStateReducer, in the app module) a distinct foreground-service
 * notification, per V7/V8.
 */
public enum class VoiceUiState { LISTENING, THINKING, SPEAKING }

/** Opaque identifier for a distinct earcon sound. Kept as a plain value type
 * (not a resource ID) so this module has no Android resource dependency and
 * remains a plain-JVM-testable seam; the app module's EarconPlayer
 * implementation is responsible for mapping these to actual sound resources.
 */
public enum class Earcon { LISTENING_START, THINKING_START, SPEAKING_START }

/**
 * Plays a distinct earcon for each [VoiceUiState] transition (V7). Kept as a
 * narrow interface so state-transition mapping logic (see [EarconMapper]) is
 * unit-testable independent of any real audio playback implementation.
 */
public interface EarconPlayer {
    public fun play(earcon: Earcon)
}

/**
 * Pure mapping from [VoiceUiState] to the [Earcon] that should play when
 * entering that state. Kept separate from [EarconPlayer] so the mapping logic
 * itself - which state maps to which earcon - is trivially unit-testable
 * without any player implementation at all.
 */
public object EarconMapper {
    public fun earconFor(state: VoiceUiState): Earcon = when (state) {
        VoiceUiState.LISTENING -> Earcon.LISTENING_START
        VoiceUiState.THINKING -> Earcon.THINKING_START
        VoiceUiState.SPEAKING -> Earcon.SPEAKING_START
    }
}

/**
 * Drives an [EarconPlayer] from a sequence of [VoiceUiState] transitions,
 * playing the earcon for each *new* state and skipping repeats of the same
 * state (no re-triggering the same earcon on redundant transitions).
 */
public class EarconStateDriver(private val player: EarconPlayer) {
    private var lastState: VoiceUiState? = null

    public fun onStateChanged(newState: VoiceUiState) {
        if (newState == lastState) return
        lastState = newState
        player.play(EarconMapper.earconFor(newState))
    }
}
