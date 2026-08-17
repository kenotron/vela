package com.vela.voice

/**
 * Push-to-talk (PTT) fallback state machine (V6: hard mute / PTT fallback always
 * available, one tap away).
 *
 * This is a small, standalone, purely-synchronous state machine with no
 * dependency on LiveKit, Android, or coroutines - fully unit-testable without
 * an emulator.
 *
 * States:
 *  - OFF: PTT mode is not engaged; voice transport behaves in normal
 *    always-listening mode (subject to its own turn detection).
 *  - ARMED: user has toggled PTT on but is not currently pressing to talk;
 *    the mic is hard-muted while armed and idle.
 *  - TRANSMITTING: user is actively holding/pressing talk; mic is live.
 *
 * Transitions:
 *  - toggle() from OFF -> ARMED, from ARMED/TRANSMITTING -> OFF (one tap to
 *    fully disengage PTT fallback and hard-mute, per V6).
 *  - press() only valid from ARMED -> TRANSMITTING.
 *  - release() only valid from TRANSMITTING -> ARMED.
 *  - press()/release() while OFF are no-ops (PTT gesture is ignored when the
 *    fallback mode itself isn't engaged).
 */
public class PttController(initialState: State = State.OFF) {

    public enum class State { OFF, ARMED, TRANSMITTING }

    public var state: State = initialState
        private set

    /** True whenever the microphone should be considered live/unmuted. */
    public val isMicLive: Boolean
        get() = state == State.TRANSMITTING || state == State.OFF

    /** Toggle PTT fallback mode on/off. One tap, per V6. */
    public fun toggle() {
        state = when (state) {
            State.OFF -> State.ARMED
            State.ARMED, State.TRANSMITTING -> State.OFF
        }
    }

    /** Begin transmitting. Only has an effect when currently ARMED. */
    public fun press() {
        if (state == State.ARMED) {
            state = State.TRANSMITTING
        }
    }

    /** Stop transmitting, returning to ARMED (still hard-muted). Only has an effect when TRANSMITTING. */
    public fun release() {
        if (state == State.TRANSMITTING) {
            state = State.ARMED
        }
    }
}
