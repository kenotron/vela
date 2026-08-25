package com.vela.auto

import com.vela.voice.classifier.UtteranceClassifier
import com.vela.voice.handoff.SlowTierEvent
import com.vela.voice.handoff.SlowTierGateway
import com.vela.voice.handoff.TierCoordinator
import com.vela.voice.narrator.NarrationSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoVoiceSessionFlowTest {

    private class FixedClassifier(private val result: UtteranceClassifier.Classification) : UtteranceClassifier {
        override fun classify(utterance: String): UtteranceClassifier.Classification = result
    }

    private class RecordingGateway(private val events: List<SlowTierEvent>) : SlowTierGateway {
        override fun dispatch(utterance: String): Flow<SlowTierEvent> = flowOf(*events.toTypedArray())
    }

    private class RecordingNarrationSink : NarrationSink {
        val narrated = mutableListOf<String>()
        override fun narrate(text: String) {
            narrated += text
        }
    }

    @Test
    fun `trivial utterance starts and completes the session with a direct response`() = runTest {
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.TRIVIAL),
            slowTierGateway = RecordingGateway(emptyList()),
        )
        val tracker = InMemoryAutoVoiceSessionTracker()
        val flow = AutoVoiceSessionFlow(coordinator, tracker)

        val results = flow.start("hey car, what time is it").toList()

        assertEquals(listOf(AutoVoiceSessionFlow.SessionResult.RespondedDirectly("hey car, what time is it")), results)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.startedCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(1, snapshot.succeededCount)
        assertEquals(0, snapshot.failedCount)
    }

    @Test
    fun `real work utterance narrates progress and reports a Completed session result`() = runTest {
        val gateway = RecordingGateway(
            listOf(
                SlowTierEvent.Progress("checking traffic..."),
                SlowTierEvent.Completed("Rerouted around the accident on I-90."),
            ),
        )
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
        )
        val tracker = InMemoryAutoVoiceSessionTracker()
        val flow = AutoVoiceSessionFlow(coordinator, tracker)
        val sink = RecordingNarrationSink()

        val results = flow.start("find a faster route", sink).toList()

        assertEquals(
            listOf(AutoVoiceSessionFlow.SessionResult.Completed("Rerouted around the accident on I-90.")),
            results,
        )
        assertEquals(listOf("checking traffic..."), sink.narrated)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.startedCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(1, snapshot.succeededCount)
        assertEquals(0, snapshot.failedCount)
    }

    @Test
    fun `slow-tier failure reports a Failed session result and records failure in the tracker`() = runTest {
        val gateway = RecordingGateway(listOf(SlowTierEvent.Failed("could not reach the maps service")))
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
        )
        val tracker = InMemoryAutoVoiceSessionTracker()
        val flow = AutoVoiceSessionFlow(coordinator, tracker)

        val results = flow.start("find a gas station").toList()

        assertEquals(listOf(AutoVoiceSessionFlow.SessionResult.Failed("could not reach the maps service")), results)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.startedCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(0, snapshot.succeededCount)
        assertEquals(1, snapshot.failedCount)
    }

    @Test
    fun `tracker records session start before any terminal event and completion after`() = runTest {
        val gateway = RecordingGateway(listOf(SlowTierEvent.Completed("done")))
        val coordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.REAL_WORK),
            slowTierGateway = gateway,
        )
        val tracker = InMemoryAutoVoiceSessionTracker()
        val flow = AutoVoiceSessionFlow(coordinator, tracker)

        flow.start("play some music").toList()

        val records = tracker.records()
        assertEquals(2, records.size)
        assertEquals(InMemoryAutoVoiceSessionTracker.SessionRecord.Kind.STARTED, records[0].kind)
        assertEquals(InMemoryAutoVoiceSessionTracker.SessionRecord.Kind.COMPLETED, records[1].kind)
        assertTrue(records[1].success == true)
    }

    @Test
    fun `multiple sessions accumulate in the tracker snapshot`() = runTest {
        val trivialCoordinator = TierCoordinator(
            classifier = FixedClassifier(UtteranceClassifier.Classification.TRIVIAL),
            slowTierGateway = RecordingGateway(emptyList()),
        )
        val tracker = InMemoryAutoVoiceSessionTracker()
        val flow = AutoVoiceSessionFlow(trivialCoordinator, tracker)

        flow.start("first utterance").toList()
        flow.start("second utterance").toList()
        flow.start("third utterance").toList()

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot.startedCount)
        assertEquals(3, snapshot.completedCount)
        assertEquals(3, snapshot.succeededCount)
        assertEquals(0, snapshot.failedCount)
    }
}
