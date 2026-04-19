package com.vela.app.events

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EventBusTest {

    private val bus = EventBus()

    @Test
    fun `tryPublish returns true when buffer not full`() {
        val result = bus.tryPublish("recipe:ready", """{"count":3}""")
        assertThat(result).isTrue()
    }

    @Test
    fun `published event arrives on events flow with correct topic and payload`() = runBlocking {
        val received = mutableListOf<VelaEvent>()
        val job = launch { bus.events.collect { received += it } }

        delay(10) // let collector subscribe
        bus.tryPublish("vela:theme-changed", "{}")
        delay(50)
        job.cancel()

        assertThat(received).hasSize(1)
        assertThat(received[0].topic).isEqualTo("vela:theme-changed")
        assertThat(received[0].payload).isEqualTo("{}")
    }

    @Test
    fun `VelaEvent holds topic and payload`() {
        val event = VelaEvent("vela:vault-changed", """{"path":"notes/today.md"}""")
        assertThat(event.topic).isEqualTo("vela:vault-changed")
        assertThat(event.payload).isEqualTo("""{"path":"notes/today.md"}""")
    }

    @Test
    fun `multiple events published in sequence all arrive`() = runBlocking {
        val received = mutableListOf<VelaEvent>()
        val job = launch { bus.events.collect { received += it } }

        delay(10) // let collector subscribe
        bus.tryPublish("topic-a", "p1")
        bus.tryPublish("topic-b", "p2")
        delay(50)
        job.cancel()

        assertThat(received).hasSize(2)
        assertThat(received.map { it.topic }).containsExactly("topic-a", "topic-b").inOrder()
    }
}
