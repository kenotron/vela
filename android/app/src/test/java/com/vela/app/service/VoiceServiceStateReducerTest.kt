package com.vela.app.service

import com.vela.core.domain.VoiceTransport
import com.vela.voice.Earcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceServiceStateReducerTest {

    @Test
    fun `CONNECTED + LISTENING yields Listening notification and LISTENING_START earcon`() {
        val reducer = VoiceServiceStateReducer()
        val output = reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.LISTENING)
        assertEquals("Listening…", output.notification.text)
        assertEquals(Earcon.LISTENING_START, output.earcon)
    }

    @Test
    fun `CONNECTED + THINKING yields Thinking notification and THINKING_START earcon`() {
        val reducer = VoiceServiceStateReducer()
        val output = reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.THINKING)
        assertEquals("Thinking…", output.notification.text)
        assertEquals(Earcon.THINKING_START, output.earcon)
    }

    @Test
    fun `CONNECTED + SPEAKING yields Speaking notification and SPEAKING_START earcon`() {
        val reducer = VoiceServiceStateReducer()
        val output = reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.SPEAKING)
        assertEquals("Speaking…", output.notification.text)
        assertEquals(Earcon.SPEAKING_START, output.earcon)
    }

    @Test
    fun `DISCONNECTED never plays an earcon regardless of substate`() {
        val reducer = VoiceServiceStateReducer()
        val output = reducer.reduce(VoiceTransport.TransportState.DISCONNECTED, VoiceSessionSubstate.SPEAKING)
        assertEquals("Voice session ended", output.notification.text)
        assertNull(output.earcon)
    }

    @Test
    fun `CONNECTING and RECONNECTING and ERROR each get distinct notification text`() {
        val reducer = VoiceServiceStateReducer()
        val connecting = reducer.reduce(VoiceTransport.TransportState.CONNECTING, VoiceSessionSubstate.LISTENING)
        val reconnecting = reducer.reduce(VoiceTransport.TransportState.RECONNECTING, VoiceSessionSubstate.LISTENING)
        val error = reducer.reduce(VoiceTransport.TransportState.ERROR, VoiceSessionSubstate.LISTENING)

        val texts = listOf(connecting.notification.text, reconnecting.notification.text, error.notification.text)
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun `repeated identical combined state does not re-trigger the earcon`() {
        val reducer = VoiceServiceStateReducer()
        reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.LISTENING)
        val second = reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.LISTENING)
        assertNull(second.earcon)
    }

    @Test
    fun `substate change while remaining CONNECTED re-triggers a new earcon`() {
        val reducer = VoiceServiceStateReducer()
        reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.LISTENING)
        val second = reducer.reduce(VoiceTransport.TransportState.CONNECTED, VoiceSessionSubstate.THINKING)
        assertEquals(Earcon.THINKING_START, second.earcon)
    }
}
