package com.vela.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

interface AudioRecordWrapper {
    fun startRecording()
    fun stop()
    fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun release()
}

class VoiceCapture(
    private val outputDir: File,
    private val audioRecordFactory: () -> AudioRecordWrapper,
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitsPerSample: Int = 16,
) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private var outputFile: File? = null
    private var recorder: AudioRecordWrapper? = null
    private var recordingThread: Thread? = null

    fun startCapture() {
        val timestamp = System.currentTimeMillis()
        val file = File(outputDir, "recording_$timestamp.wav")
        outputFile = file

        val rec = audioRecordFactory()
        recorder = rec

        _isRecording.value = true

        val thread = Thread {
            FileOutputStream(file).use { fos ->
                // Write 44-byte placeholder WAV header
                fos.write(ByteArray(44))

                rec.startRecording()

                val bufferSize = sampleRate / 10 * channelCount * (bitsPerSample / 8)
                val buffer = ByteArray(bufferSize)

                while (_isRecording.value) {
                    val bytesRead = rec.read(buffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    }
                    Thread.sleep(10)
                }

                fos.flush()
            }
            writeWavHeader(file)
        }
        recordingThread = thread
        thread.start()
    }

    fun stopCapture(): String? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        recordingThread?.join(2000)
        recorder?.stop()
        recorder?.release()

        return outputFile?.absolutePath
    }

    private fun writeWavHeader(file: File) {
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val dataSize = maxOf(0, file.length().toInt() - 44)
        val chunkSize = dataSize + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            val header = ByteArray(44).apply {
                // RIFF marker
                this[0] = 'R'.code.toByte()
                this[1] = 'I'.code.toByte()
                this[2] = 'F'.code.toByte()
                this[3] = 'F'.code.toByte()
                // Chunk size (file size - 8), little-endian
                this[4] = (chunkSize and 0xFF).toByte()
                this[5] = ((chunkSize shr 8) and 0xFF).toByte()
                this[6] = ((chunkSize shr 16) and 0xFF).toByte()
                this[7] = ((chunkSize shr 24) and 0xFF).toByte()
                // WAVE marker
                this[8] = 'W'.code.toByte()
                this[9] = 'A'.code.toByte()
                this[10] = 'V'.code.toByte()
                this[11] = 'E'.code.toByte()
                // fmt chunk marker
                this[12] = 'f'.code.toByte()
                this[13] = 'm'.code.toByte()
                this[14] = 't'.code.toByte()
                this[15] = ' '.code.toByte()
                // fmt chunk size (16 for PCM), little-endian
                this[16] = 16
                this[17] = 0
                this[18] = 0
                this[19] = 0
                // PCM audio format (1), little-endian
                this[20] = 1
                this[21] = 0
                // Number of channels, little-endian
                this[22] = (channelCount and 0xFF).toByte()
                this[23] = ((channelCount shr 8) and 0xFF).toByte()
                // Sample rate, little-endian
                this[24] = (sampleRate and 0xFF).toByte()
                this[25] = ((sampleRate shr 8) and 0xFF).toByte()
                this[26] = ((sampleRate shr 16) and 0xFF).toByte()
                this[27] = ((sampleRate shr 24) and 0xFF).toByte()
                // Byte rate, little-endian
                this[28] = (byteRate and 0xFF).toByte()
                this[29] = ((byteRate shr 8) and 0xFF).toByte()
                this[30] = ((byteRate shr 16) and 0xFF).toByte()
                this[31] = ((byteRate shr 24) and 0xFF).toByte()
                // Block align, little-endian
                this[32] = (blockAlign and 0xFF).toByte()
                this[33] = ((blockAlign shr 8) and 0xFF).toByte()
                // Bits per sample, little-endian
                this[34] = (bitsPerSample and 0xFF).toByte()
                this[35] = ((bitsPerSample shr 8) and 0xFF).toByte()
                // data chunk marker
                this[36] = 'd'.code.toByte()
                this[37] = 'a'.code.toByte()
                this[38] = 't'.code.toByte()
                this[39] = 'a'.code.toByte()
                // Data size, little-endian
                this[40] = (dataSize and 0xFF).toByte()
                this[41] = ((dataSize shr 8) and 0xFF).toByte()
                this[42] = ((dataSize shr 16) and 0xFF).toByte()
                this[43] = ((dataSize shr 24) and 0xFF).toByte()
            }
            raf.write(header)
        }
    }
}
