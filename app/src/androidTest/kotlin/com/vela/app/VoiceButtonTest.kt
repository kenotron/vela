package com.vela.app

import android.Manifest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceButtonTest {

    @get:Rule(order = 0)
    val grantPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun voiceButtonShowsStartStateInitially() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesAfterTap() {
        val startButton = checkNotNull(device.findObject(By.desc("Start voice input"))) {
            "Start voice input button not found in accessibility tree"
        }
        startButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesBackAfterSecondTap() {
        val startButton = checkNotNull(device.findObject(By.desc("Start voice input"))) {
            "Start voice input button not found in accessibility tree"
        }
        startButton.click()
        device.waitForIdle(2000)
        val stopButton = checkNotNull(device.findObject(By.desc("Stop voice input"))) {
            "Stop voice input button not found in accessibility tree"
        }
        stopButton.click()
        device.waitForIdle(2000)
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Stop voice input")
    }
}
