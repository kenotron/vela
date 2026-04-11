package com.vela.app.util

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import java.io.File

object AccessibilitySnapshot {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun dump(): String {
        device.waitForIdle(3000)
        val tmpFile = File.createTempFile("accessibility_snapshot", ".xml")
        try {
            device.dumpWindowHierarchy(tmpFile)
            return tmpFile.readText()
        } finally {
            tmpFile.delete()
        }
    }

    fun assertHasContentDesc(contentDesc: String) {
        val snapshot = dump()
        assertThat(snapshot).contains("content-desc=\"$contentDesc\"")
    }

    fun assertHasText(text: String) {
        val snapshot = dump()
        assertThat(snapshot).contains("text=\"$text\"")
    }

    fun assertNotHasContentDesc(contentDesc: String) {
        val snapshot = dump()
        assertThat(snapshot).doesNotContain("content-desc=\"$contentDesc\"")
    }
}
