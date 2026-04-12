package com.vela.app.ai

    import com.google.common.truth.Truth.assertThat
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow
    import kotlinx.coroutines.flow.toList
    import kotlinx.coroutines.test.runTest
    import org.junit.Test


    class MlKitGemma4EngineTest {

        enum class FakeFeatureStatus { AVAILABLE, DOWNLOADING, DOWNLOADABLE, UNAVAILABLE }

        inner class FakeMlKitModel(
            var responseText: String = "fake response",
            var featureStatus: FakeFeatureStatus = FakeFeatureStatus.AVAILABLE,
            var closed: Boolean = false,
            var downloadCalled: Boolean = false,
            var lastInput: String = "",
        ) : MlKitModelPort {
            override val isClosed: Boolean get() = closed
            override suspend fun checkStatus(): ReadinessState = when (featureStatus) {
                FakeFeatureStatus.AVAILABLE -> ReadinessState.Available
                FakeFeatureStatus.DOWNLOADING -> ReadinessState.Downloading(null)
                FakeFeatureStatus.DOWNLOADABLE -> ReadinessState.Downloadable
                FakeFeatureStatus.UNAVAILABLE -> ReadinessState.Unavailable
            }
            override suspend fun download() { downloadCalled = true }
            override suspend fun generate(input: String): String { lastInput = input; return responseText }

            /** Simulate streaming by emitting each word as a separate chunk. */
            override fun generateStream(input: String): Flow<String> = flow {
                lastInput = input
                val words = responseText.split(" ")
                words.forEachIndexed { index, word ->
                    emit(if (index == 0) word else " $word")
                }
            }

            override fun close() { closed = true }
        }

        @Test
        fun processTextReturnsNonEmptyString() = runTest {
            val fake = FakeMlKitModel(responseText = "Hello from Gemma 4")
            val engine = MlKitGemma4Engine(fakeModel = fake)
            val result = engine.processText("hello")
            assertThat(result).isEqualTo("Hello from Gemma 4")
        }

        @Test
        fun checkReadinessReturnsAvailableWhenModelReady() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.AVAILABLE)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            assertThat(engine.checkReadiness()).isEqualTo(ReadinessState.Available)
        }

        @Test
        fun checkReadinessReturnsDownloadableWhenModelNeedsDownload() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.DOWNLOADABLE)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            assertThat(engine.checkReadiness()).isEqualTo(ReadinessState.Downloadable)
        }

        @Test
        fun checkReadinessReturnsDownloadingWhenInProgress() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.DOWNLOADING)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            assertThat(engine.checkReadiness()).isInstanceOf(ReadinessState.Downloading::class.java)
        }

        @Test
        fun checkReadinessReturnsUnavailableForUnsupportedDevice() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.UNAVAILABLE)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            assertThat(engine.checkReadiness()).isEqualTo(ReadinessState.Unavailable)
        }

        @Test
        fun processTextThrowsWhenModelClosed() = runTest {
            val fake = FakeMlKitModel(closed = true)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            var caught: IllegalStateException? = null
            try {
                engine.processText("hello")
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertThat(caught).isNotNull()
        }

        @Test
        fun implementsGemmaEngineInterface() {
            val fake = FakeMlKitModel()
            val engine = MlKitGemma4Engine(fakeModel = fake)
            assertThat(engine).isInstanceOf(GemmaEngine::class.java)
        }

        @Test
        fun ensureReadyTriggersDownloadWhenDownloadable() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.DOWNLOADABLE)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            engine.ensureReady()
            assertThat(fake.downloadCalled).isTrue()
        }

        @Test
        fun ensureReadySkipsDownloadWhenAlreadyAvailable() = runTest {
            val fake = FakeMlKitModel(featureStatus = FakeFeatureStatus.AVAILABLE)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            engine.ensureReady()
            assertThat(fake.downloadCalled).isFalse()
        }

        @Test
        fun processTextTruncatesInputOver3500Chars() = runTest {
            val fake = FakeMlKitModel()
            val engine = MlKitGemma4Engine(fakeModel = fake)
            val longInput = "a".repeat(3501)
            engine.processText(longInput)
            assertThat(fake.lastInput.length).isAtMost(3515)
            assertThat(fake.lastInput).endsWith("...[truncated]")
        }

        // --- Streaming tests ---

        @Test
        fun streamTextEmitsAtLeastOneChunk() = runTest {
            val fake = FakeMlKitModel(responseText = "hello world")
            val engine = MlKitGemma4Engine(fakeModel = fake)
            val chunks = engine.streamText("hi").toList()
            assertThat(chunks).isNotEmpty()
        }

        @Test
        fun streamTextChunksAccumulateIntoFullResponse() = runTest {
            val expected = "alpha beta gamma"
            val fake = FakeMlKitModel(responseText = expected)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            val accumulated = engine.streamText("test").toList().joinToString("")
            assertThat(accumulated).isEqualTo(expected)
        }

        @Test
        fun streamTextThrowsWhenModelClosed() = runTest {
            val fake = FakeMlKitModel(closed = true)
            val engine = MlKitGemma4Engine(fakeModel = fake)
            var caught: IllegalStateException? = null
            try {
                engine.streamText("hello").toList()
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertThat(caught).isNotNull()
        }

        @Test
        fun streamTextTruncatesInputOver3500Chars() = runTest {
            val fake = FakeMlKitModel()
            val engine = MlKitGemma4Engine(fakeModel = fake)
            val longInput = "x".repeat(3501)
            engine.streamText(longInput).toList()
            assertThat(fake.lastInput).endsWith("...[truncated]")
        }
    }
    