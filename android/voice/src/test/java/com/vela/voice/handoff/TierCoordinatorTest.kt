package com.vela.voice.handoff

import com.vela.voice.classifier.UtteranceClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TierCoordinatorTest {

    private class FixedClassifier(private val result: UtteranceClassifier.Classification) : UtteranceClassifier {
        override fun classify(utterance: String): UtteranceClassifier.Classification = result
    }

    private class RecordingGateway(private val events: List<SlowTierEvent>) : SlowTierGateway {
        var lastDispatchedUtterance: String? = null
        override fun dispatch(utterance: String): Flow<SlowTierEvent> {
            lastDispatchedUtterance = utterance
            return flowOf(*events.toTypedArray())
        }
    }

    private class RecordingNarrationSink : com.vela.voice.narrator.NarrationSink {
        val narrated = mutableListOf<String>()
        override fun narrate(text: String) {
            narrated += text
        }
    }

    @Test
    fun `trivial utterance responds directly with no slow-tier dispatch`() = runTest {
        val gateway = RecordingGateway(emptyList())
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.TRIVIAL),
            slowTierGateway = gateway,
        )

        val events = coordinator.handle("hello").toList()

        assertEquals(listOf(TierCoordinator.TierEvent.RespondDirectly("hello")), events)
        assertEquals(null, gateway.lastDispatchedUtterance)
    }

    @Test
    fun `real work utterance acknowledges, narrates real progress, and folds back the result`() = runTest {
        val gateway = RecordingGateway(
            listOf(
                SlowTierEvent.Progress("checking the calendar..."),
                SlowTierEvent.Progress("delegating to research..."),
                SlowTierEvent.Completed("Booked for 3pm Thursday."),
            ),
        )
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
            acknowledgementText = { "I'll get on that." },
        )
        val sink = RecordingNarrationSink()

        val events = coordinator.handle("schedule a meeting for thursday", sink).toList()

        assertEquals(
            listOf(
                TierCoordinator.TierEvent.Acknowledged("schedule a meeting for thursday", "I'll get on that."),
                TierCoordinator.TierEvent.Narrating("checking the calendar..."),
                TierCoordinator.TierEvent.Narrating("delegating to research..."),
                TierCoordinator.TierEvent.Completed("Booked for 3pm Thursday."),
            ),
            events,
        )
        assertEquals("schedule a meeting for thursday", gateway.lastDispatchedUtterance)
        // narration-while-waiting: sink received the same real progress lines, not synthetic filler
        assertEquals(listOf("checking the calendar...", "delegating to research..."), sink.narrated)
    }

    @Test
    fun `real work failure folds back a Failed event, not a crash`() = runTest {
        val gateway = RecordingGateway(
            listOf(
                SlowTierEvent.Progress("looking into it..."),
                SlowTierEvent.Failed("could not reach the calendar service"),
            ),
        )
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
        )

        val events = coordinator.handle("book a flight").toList()

        assertTrue(events.first() is TierCoordinator.TierEvent.Acknowledged)
        assertEquals(TierCoordinator.TierEvent.Failed("could not reach the calendar service"), events.last())
    }

    @Test
    fun `narration sink is optional -- callers can collect the flow directly instead`() = runTest {
        val gateway = RecordingGateway(listOf(SlowTierEvent.Completed("done")))
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
        )

        val events = coordinator.handle("send the email").toList()

        assertEquals(2, events.size)
    }
}
