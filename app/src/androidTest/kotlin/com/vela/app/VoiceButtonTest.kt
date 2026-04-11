package com.vela.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceButtonTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun voiceButtonShowsStartStateInitially() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesAfterTap() {
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesBackAfterSecondTap() {
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)
        val stopButton = device.findObject(By.desc("Stop voice input"))
        stopButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Stop voice input")
    }
}
