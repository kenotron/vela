package com.vela.voice

import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingEarconPlayer : EarconPlayer {
    val played = mutableListOf<Earcon>()
    override fun play(earcon: Earcon) {
        played.add(earcon)
    }
}

class EarconMapperTest {
    @Test
    fun `each VoiceUiState maps to a distinct earcon`() {
        val mapped = VoiceUiState.entries.map { EarconMapper.earconFor(it) }
        assertEquals(mapped.size, mapped.toSet().size)
    }

    @Test
    fun `LISTENING maps to LISTENING_START`() {
        assertEquals(Earcon.LISTENING_START, EarconMapper.earconFor(VoiceUiState.LISTENING))
    }

    @Test
    fun `THINKING maps to THINKING_START`() {
        assertEquals(Earcon.THINKING_START, EarconMapper.earconFor(VoiceUiState.THINKING))
    }

    @Test
    fun `SPEAKING maps to SPEAKING_START`() {
        assertEquals(Earcon.SPEAKING_START, EarconMapper.earconFor(VoiceUiState.SPEAKING))
    }
}

class EarconStateDriverTest {
    @Test
    fun `plays earcon for each new state transition`() {
        val player = RecordingEarconPlayer()
        val driver = EarconStateDriver(player)

        driver.onStateChanged(VoiceUiState.LISTENING)
        driver.onStateChanged(VoiceUiState.THINKING)
        driver.onStateChanged(VoiceUiState.SPEAKING)

        assertEquals(
            listOf(Earcon.LISTENING_START, Earcon.THINKING_START, Earcon.SPEAKING_START),
            player.played,
        )
    }

    @Test
    fun `does not re-trigger earcon for a repeated identical state`() {
        val player = RecordingEarconPlayer()
        val driver = EarconStateDriver(player)

        driver.onStateChanged(VoiceUiState.LISTENING)
        driver.onStateChanged(VoiceUiState.LISTENING)

        assertEquals(listOf(Earcon.LISTENING_START), player.played)
    }
}
