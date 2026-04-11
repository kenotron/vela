package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun initialStateIsIdle() {
        val voiceCapture = VoiceCapture(
            outputDir = tempFolder.root,
            audioRecordFactory = { FakeAudioRecord() },
        )
        assertThat(voiceCapture.isRecording.value).isFalse()
    }

    @Test
    fun startCaptureTransitionsToRecording() {
        val voiceCapture = VoiceCapture(
            outputDir = tempFolder.root,
            audioRecordFactory = { FakeAudioRecord() },
        )
        voiceCapture.startCapture()
        assertThat(voiceCapture.isRecording.value).isTrue()
    }

    @Test
    fun stopCaptureTransitionsToIdleAndReturnsFilePath() {
        val voiceCapture = VoiceCapture(
            outputDir = tempFolder.root,
            audioRecordFactory = { FakeAudioRecord() },
        )
        voiceCapture.startCapture()
        val filePath = voiceCapture.stopCapture()
        assertThat(voiceCapture.isRecording.value).isFalse()
        assertThat(filePath).isNotNull()
        val file = File(filePath!!)
        assertThat(file.exists()).isTrue()
        assertThat(file.extension).isEqualTo("wav")
    }

    @Test
    fun stopCaptureWithoutStartReturnsNull() {
        val voiceCapture = VoiceCapture(
            outputDir = tempFolder.root,
            audioRecordFactory = { FakeAudioRecord() },
        )
        val filePath = voiceCapture.stopCapture()
        assertThat(filePath).isNull()
    }
}

private class FakeAudioRecord : AudioRecordWrapper {
    override fun startRecording() {}

    override fun stop() {}

    override fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        buffer.fill(0)
        return sizeInBytes
    }

    override fun release() {}
}
