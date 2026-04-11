package com.vela.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun conversationScreenShowsVoiceButton() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun tappingVoiceButtonTogglesState() {
        val startButton = checkNotNull(device.findObject(By.desc("Start voice input"))) {
            "Start voice input button not found in accessibility tree"
        }
        startButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
    }

    @Test
    fun fullConversationFlowShowsAssistantResponse() {
        val startButton = checkNotNull(device.findObject(By.desc("Start voice input"))) {
            "Start voice input button not found in accessibility tree"
        }
        startButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
        val stopButton = checkNotNull(device.findObject(By.desc("Stop voice input"))) {
            "Stop voice input button not found in accessibility tree"
        }
        stopButton.click()
        device.wait(
            Until.findObject(By.text("Hello! I'm Vela, your on-device AI assistant. How can I help?")),
            5000,
        )
        AccessibilitySnapshot.assertHasText("Hello! I'm Vela, your on-device AI assistant. How can I help?")
    }
}
