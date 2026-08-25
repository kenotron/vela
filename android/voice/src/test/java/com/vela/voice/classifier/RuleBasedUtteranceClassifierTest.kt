package com.vela.voice.classifier

import org.junit.Test
import org.junit.Assert.assertEquals

class RuleBasedUtteranceClassifierTest {

    private val classifier = RuleBasedUtteranceClassifier()

    @Test
    fun `blank utterance is trivial`() {
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify(""))
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("   "))
    }

    @Test
    fun `short greetings are trivial`() {
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("Hey there"))
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("thanks"))
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("ok cool"))
    }

    @Test
    fun `short clarifying questions are trivial`() {
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("what time is it"))
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, classifier.classify("who are you"))
    }

    @Test
    fun `utterances with action keywords are real work regardless of case`() {
        assertEquals(
            UtteranceClassifier.Classification.REAL_WORK,
            classifier.classify("Schedule a meeting with the team for tomorrow at 3pm"),
        )
        assertEquals(
            UtteranceClassifier.Classification.REAL_WORK,
            classifier.classify("SEND an email to Jordan about the invoice"),
        )
        assertEquals(
            UtteranceClassifier.Classification.REAL_WORK,
            classifier.classify("remind me to call the dentist"),
        )
        assertEquals(
            UtteranceClassifier.Classification.REAL_WORK,
            classifier.classify("cancel my 2pm"),
        )
        assertEquals(
            UtteranceClassifier.Classification.REAL_WORK,
            classifier.classify("research the best flight options to Denver next week"),
        )
    }

    @Test
    fun `short action utterance is still real work -- keyword wins over length`() {
        assertEquals(UtteranceClassifier.Classification.REAL_WORK, classifier.classify("call mom"))
    }

    @Test
    fun `long utterance with no action keyword defaults to trivial (safe failure direction)`() {
        assertEquals(
            UtteranceClassifier.Classification.TRIVIAL,
            classifier.classify("I was just thinking out loud about how nice the weather has been lately"),
        )
    }

    @Test
    fun `custom config can tune trivialMaxWords and keywords`() {
        val custom = RuleBasedUtteranceClassifier(
            RuleBasedUtteranceClassifier.Config(
                trivialMaxWords = 1,
                actionKeywords = setOf("frobnicate"),
            ),
        )
        assertEquals(UtteranceClassifier.Classification.TRIVIAL, custom.classify("ok"))
        assertEquals(UtteranceClassifier.Classification.REAL_WORK, custom.classify("please frobnicate the widget"))
    }
}
