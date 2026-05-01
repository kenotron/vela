package com.vela.app.voice

    import android.content.Context
    import android.media.MediaRecorder
    import java.io.File

    /**
     * Wraps Android [MediaRecorder] for voice capture.
     *
     * Records AAC audio at 16 kHz to an .m4a file in the app's cache directory.
     * The file is suitable for submission to OpenAI's Whisper transcription API.
     *
     * Usage:
     *   val recorder = AudioRecorder(context)
     *   val file = recorder.start()   // begins recording, returns File reference
     *   val file = recorder.stop()    // stops recording, returns the same file (nullable)
     */
    class AudioRecorder(private val context: Context) {

        private var recorder: MediaRecorder? = null
        private var outputFile: File? = null

        /** Begin recording. Returns the output [File] that will be written. */
        fun start(): File {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            @Suppress("DEPRECATION")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            return file
        }

        /** Stop recording and release resources. Returns the completed audio file. */
        fun stop(): File? {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            return outputFile
        }
    }
    