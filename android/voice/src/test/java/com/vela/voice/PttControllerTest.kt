package com.vela.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttControllerTest {

    @Test
    fun `starts OFF with mic live by default (normal always-listening mode)`() {
        val ptt = PttController()
        assertEquals(PttController.State.OFF, ptt.state)
        assertTrue(ptt.isMicLive)
    }

    @Test
    fun `toggle from OFF arms PTT and hard-mutes mic`() {
        val ptt = PttController()
        ptt.toggle()
        assertEquals(PttController.State.ARMED, ptt.state)
        assertFalse(ptt.isMicLive)
    }

    @Test
    fun `press while ARMED transitions to TRANSMITTING and unmutes`() {
        val ptt = PttController()
        ptt.toggle() // OFF -> ARMED
        ptt.press()
        assertEquals(PttController.State.TRANSMITTING, ptt.state)
        assertTrue(ptt.isMicLive)
    }

    @Test
    fun `release while TRANSMITTING returns to ARMED and re-mutes`() {
        val ptt = PttController()
        ptt.toggle()
        ptt.press()
        ptt.release()
        assertEquals(PttController.State.ARMED, ptt.state)
        assertFalse(ptt.isMicLive)
    }

    @Test
    fun `press while OFF is a no-op`() {
        val ptt = PttController()
        ptt.press()
        assertEquals(PttController.State.OFF, ptt.state)
    }

    @Test
    fun `release while ARMED (not transmitting) is a no-op`() {
        val ptt = PttController()
        ptt.toggle()
        ptt.release()
        assertEquals(PttController.State.ARMED, ptt.state)
    }

    @Test
    fun `toggle from TRANSMITTING fully disengages to OFF in one tap`() {
        val ptt = PttController()
        ptt.toggle()
        ptt.press()
        ptt.toggle()
        assertEquals(PttController.State.OFF, ptt.state)
        assertTrue(ptt.isMicLive)
    }

    @Test
    fun `toggle from ARMED disengages to OFF in one tap`() {
        val ptt = PttController()
        ptt.toggle()
        ptt.toggle()
        assertEquals(PttController.State.OFF, ptt.state)
    }
}
