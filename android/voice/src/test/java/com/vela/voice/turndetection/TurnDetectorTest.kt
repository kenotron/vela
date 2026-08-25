package com.vela.voice.turndetection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TurnDetector] - no device, emulator, or LiveKit connection
 * required. Time is driven by an injected fake clock so tests are
 * deterministic and instant.
 */
class TurnDetectorTest {

    private class FakeClock(startMs: Long = 0L) {
        var nowMs: Long = startMs
        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private fun detectorWith(
        clock: FakeClock,
        config: TurnDetector.Config = TurnDetector.Config(),
    ) = TurnDetector(config = config, nowMs = { clock.nowMs })

    @Test
    fun `an isolated word with no terminal punctuation and no elapsed silence is still ongoing`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        val signal = detector.onTranscriptDelta("hello", isFinal = false)

        assertEquals(TurnDetector.Signal.TurnOngoing, signal)
    }

    @Test
    fun `a naive fixed-silence-only VAD would fire here, but content-incompleteness keeps this ongoing`() {
        // Regression guard for the V2 anti-pattern: a single short word ("um")
        // followed by a long silence must NOT be classified as end-of-turn just
        // because a silence timer expired - that is exactly the "interrupts a
        // thinking pause" failure mode V2 exists to prevent.
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("um", isFinal = true)
        clock.advance(10_000L) // far beyond any silence threshold

        val signal = detector.onSilenceTick()

        assertEquals(
            "a single below-minimum-word-count utterance must never be classified " +
                "as end-of-turn purely due to elapsed silence",
            TurnDetector.Signal.TurnOngoing,
            signal,
        )
    }

    @Test
    fun `sufficient content plus sufficient trailing silence yields TurnComplete`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today", isFinal = true)
        clock.advance(700L) // == default minTrailingSilenceMs

        val signal = detector.onSilenceTick()

        assertEquals(TurnDetector.Signal.TurnComplete, signal)
    }

    @Test
    fun `sufficient content but insufficient trailing silence remains ongoing`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today", isFinal = true)
        clock.advance(200L) // below default 700ms threshold

        val signal = detector.onSilenceTick()

        assertEquals(TurnDetector.Signal.TurnOngoing, signal)
    }

    @Test
    fun `a new delta arriving within the silence window resets the clock and stays ongoing`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather", isFinal = false)
        clock.advance(600L) // below threshold, but about to add more content anyway
        val signalAfterSecondDelta = detector.onTranscriptDelta("what is the weather today", isFinal = true)

        assertEquals(
            "a fresh delta must reset the trailing-silence clock",
            TurnDetector.Signal.TurnOngoing,
            signalAfterSecondDelta,
        )

        // Now no further deltas arrive; only after the *new* full silence window
        // elapses (measured from the second delta) should completion fire.
        clock.advance(699L)
        assertEquals(TurnDetector.Signal.TurnOngoing, detector.onSilenceTick())

        clock.advance(1L)
        assertEquals(TurnDetector.Signal.TurnComplete, detector.onSilenceTick())
    }

    @Test
    fun `terminal punctuation relaxes the required trailing silence versus a plain trailing pause`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today?", isFinal = true)
        clock.advance(250L) // == default minTrailingSilenceAfterTerminalPunctuationMs, well below the 700ms plain threshold

        val signal = detector.onSilenceTick()

        assertEquals(
            "terminal punctuation is a content-completeness signal in its own right and " +
                "should allow completion with a shorter trailing silence than a non-punctuated pause",
            TurnDetector.Signal.TurnComplete,
            signal,
        )
    }

    @Test
    fun `without a final delta, turn is never classified complete regardless of silence`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today", isFinal = false)
        clock.advance(5_000L)

        val signal = detector.onSilenceTick()

        assertEquals(TurnDetector.Signal.TurnOngoing, signal)
    }

    @Test
    fun `reset clears accumulated state so a subsequent short delta is not immediately complete`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today", isFinal = true)
        clock.advance(700L)
        assertEquals(TurnDetector.Signal.TurnComplete, detector.onSilenceTick())

        detector.reset()

        // Immediately after reset, with no new delta yet, there is nothing to
        // evaluate - must not spuriously report completion from stale state.
        assertEquals(TurnDetector.Signal.TurnOngoing, detector.onSilenceTick())

        val signal = detector.onTranscriptDelta("hi", isFinal = true)
        assertEquals(TurnDetector.Signal.TurnOngoing, signal)
    }

    @Test
    fun `once complete, repeated silence ticks remain complete without a new delta`() {
        val clock = FakeClock()
        val detector = detectorWith(clock)

        detector.onTranscriptDelta("what is the weather today", isFinal = true)
        clock.advance(700L)
        assertEquals(TurnDetector.Signal.TurnComplete, detector.onSilenceTick())

        clock.advance(1_000L)
        assertEquals(TurnDetector.Signal.TurnComplete, detector.onSilenceTick())
    }
}
