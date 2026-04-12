package com.vela.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.MediaPipeGemmaEngine
import com.vela.app.ai.RealLlmInferenceWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test for real MediaPipe inference.
 *
 * Annotated @Slow — skipped in CI via assumeTrue(modelFile.exists()).
 *
 * Manual run instructions:
 *   1. Push the model to the device:
 *      adb shell mkdir -p /data/data/com.vela.app/files/models
 *      adb push <local-path>/vela-model.bin /data/data/com.vela.app/files/models/vela-model.bin
 *   2. Run only this test class:
 *      ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vela.app.MediaPipeSmokeTest
 */
@Slow
@RunWith(AndroidJUnit4::class)
class MediaPipeSmokeTest {

    private lateinit var context: Context
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        modelFile = File(context.filesDir, "models/vela-model.bin")
        assumeTrue(modelFile.exists())
    }

    @Test
    fun modelLoadsAndGeneratesResponse() = runBlocking {
        val wrapper = RealLlmInferenceWrapper(context, modelFile.absolutePath)
        val engine = MediaPipeGemmaEngine(wrapper)
        val response = engine.processText("Hello, what is 2 plus 2?")
        assertThat(response).isNotNull()
        assertThat(response).isNotEmpty()
        wrapper.close()
    }
}
