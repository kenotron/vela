# Vela Phase 2 — Real Engines Implementation Plan

> **For execution:** Use subagent-driven-development recipe with plan_path pointing to this file.

**Goal:** Replace all Phase 1 stubs with production implementations. Android SpeechRecognizer for STT, MediaPipe Tasks GenAI (Gemma 3) for on-device inference, Android TextToSpeech for audio output, and a Gemma-powered IntentExtractor for structured intent parsing.

**Builds on Phase 1:** All 15 Phase 1 tasks completed — see `2026-04-11-vela-phase1-implementation.md`. The FakeGemmaEngine stub and AccessibilitySnapshot helper from Phase 1 continue to be used in tests throughout Phase 2.

**Architecture:** Same single-module MVVM. New packages: `voice/SpeechTranscriber.kt` for STT, `ai/MediaPipeGemmaEngine.kt` + `ai/ModelManager.kt` + `ai/IntentExtractor.kt` for inference, `audio/TtsEngine.kt` for speech output, `ui/loading/ModelLoadingScreen.kt` for model download UX.

**Tech Stack:** Everything from Phase 1 plus MediaPipe Tasks GenAI 0.10.14, OkHttp 4.12.0 (for model download), MockWebServer 4.12.0 (for download tests).

**Testing strategy:** Unit tests use fake/mock implementations of all Android framework classes (SpeechRecognizer, TextToSpeech, LlmInference). Instrumented tests use AccessibilitySnapshot for all UI assertions. The real MediaPipe engine is only exercised by the `@Slow` smoke test which requires the model file to be present — skipped in CI.

**Key constraint:** Model URL (`model_download_url` in `strings.xml`) requires manual configuration by Ken. Plan includes a placeholder URL. All other functionality works with FakeGemmaEngine when model is absent.

---

## Existing Project Layout (Phase 1)

All paths relative to `/Users/ken/workspace/vela`.

```
app/src/main/kotlin/com/vela/app/
├── VelaApplication.kt           (Hilt @HiltAndroidApp)
├── MainActivity.kt              (@AndroidEntryPoint, setContent → ConversationScreen)
├── ai/
│   ├── GemmaEngine.kt           (interface: suspend fun processText(input: String): String)
│   └── FakeGemmaEngine.kt       (canned "Hello! I'm Vela..." response)
├── voice/
│   └── VoiceCapture.kt          (AudioRecordWrapper interface + VoiceCapture class)
├── domain/model/
│   └── Message.kt               (MessageRole enum + Message data class)
├── data/
│   ├── db/
│   │   ├── MessageEntity.kt
│   │   ├── MessageDao.kt
│   │   └── VelaDatabase.kt
│   └── repository/
│       ├── ConversationRepository.kt  (interface)
│       └── RoomConversationRepository.kt
├── di/
│   └── AppModule.kt             (Hilt @Module providing Database, DAO, GemmaEngine, Repository)
└── ui/
    ├── components/VoiceButton.kt
    ├── conversation/
    │   ├── ConversationScreen.kt
    │   └── ConversationViewModel.kt  (@HiltViewModel, injects GemmaEngine + ConversationRepository)
    └── theme/ (Color.kt, Theme.kt, Type.kt)

app/src/test/kotlin/com/vela/app/
├── ai/FakeGemmaEngineTest.kt     (4 tests)
├── voice/VoiceCaptureTest.kt     (4 tests)
└── ui/conversation/ConversationViewModelTest.kt  (4 tests — uses FakeConversationRepository)

app/src/androidTest/kotlin/com/vela/app/
├── util/AccessibilitySnapshot.kt  (dump, assertHasContentDesc, assertHasText, assertNotHasContentDesc)
├── ConversationScreenTest.kt      (3 tests)
└── VoiceButtonTest.kt             (3 tests)
```

**Existing test count:** 12 unit tests, 6 instrumented tests.

**Key interfaces already defined:**
- `GemmaEngine` — `suspend fun processText(input: String): String`
- `ConversationRepository` — `fun getMessages(): Flow<List<Message>>` + `suspend fun saveMessage(message: Message)`
- `AudioRecordWrapper` — `startRecording()`, `stop()`, `read()`, `release()`

**Key patterns the implementer must follow:**
- Hilt `@Module` / `@Provides` — all in `AppModule.kt`
- `ConversationViewModel` constructor: `@Inject constructor(gemmaEngine: GemmaEngine, repository: ConversationRepository)`
- Unit tests: JUnit 4, Truth assertions, `kotlinx-coroutines-test` with `StandardTestDispatcher` and `Dispatchers.setMain()`
- Instrumented tests: `ActivityScenarioRule`, `GrantPermissionRule`, `AccessibilitySnapshot` assertions
- Build uses version catalog at `gradle/libs.versions.toml`; dependency aliases like `libs.hilt.android`, `libs.androidx.room.runtime`

---

## Task 1: Add MediaPipe and OkHttp Dependencies

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Step 1: Add version entries to `gradle/libs.versions.toml`**

Open `gradle/libs.versions.toml`. At the end of the `[versions]` section (after the `datastore` line), add:

```toml
mediapipeGenai = "0.10.14"
okhttp = "4.12.0"
```

At the end of the `[libraries]` section (after the `truth` line), add:

```toml
# MediaPipe GenAI
mediapipe-tasks-genai = { group = "com.google.mediapipe", name = "tasks-genai", version.ref = "mediapipeGenai" }

# OkHttp
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

**Step 2: Add ABI filters and new dependencies to `app/build.gradle.kts`**

Inside the `android { defaultConfig { ... } }` block, after the `testInstrumentationRunner` line, add:

```kotlin
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
```

Inside the `dependencies { ... }` block, after the `// Coroutines` section and before `// Unit tests`, add:

```kotlin
    // MediaPipe GenAI for on-device Gemma inference
    implementation(libs.mediapipe.tasks.genai)

    // OkHttp for model download
    implementation(libs.okhttp)
```

In the `// Unit tests` section, after `testImplementation(libs.kotlinx.coroutines.test)`, add:

```kotlin
    testImplementation(libs.okhttp.mockwebserver)
```

**Step 3: Verify the build compiles**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. MediaPipe and OkHttp resolve without conflicts.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/build.gradle.kts gradle/libs.versions.toml && git commit -m "build: add MediaPipe Tasks GenAI 0.10.14 and OkHttp 4.12.0 dependencies"
```

---

## Task 2: Define TranscriptState and SpeechTranscriber Interface

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/voice/SpeechTranscriber.kt`
- Create: `app/src/test/kotlin/com/vela/app/voice/SpeechTranscriberTest.kt`

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/voice/SpeechTranscriberTest.kt`:

```kotlin
package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechTranscriberTest {

    @Test
    fun initialStateIsIdle() {
        val transcriber = FakeSpeechTranscriber()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun startListeningTransitionsToListening() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Listening)
    }

    @Test
    fun emitPartialUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        transcriber.emitPartial("hel")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Partial("hel"))
    }

    @Test
    fun emitFinalUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        transcriber.emitFinal("hello world")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Final("hello world"))
    }

    @Test
    fun stopListeningFromListeningGoesToIdle() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        transcriber.stopListening()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun emitErrorUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        transcriber.emitError("Network error")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Error("Network error"))
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.SpeechTranscriberTest"
```
Expected: Compilation fails with `Unresolved reference: TranscriptState`, `Unresolved reference: FakeSpeechTranscriber`, `Unresolved reference: SpeechTranscriber`.

**Step 3: Write the interface and fake implementation**

Create `app/src/main/kotlin/com/vela/app/voice/SpeechTranscriber.kt`:

```kotlin
package com.vela.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TranscriptState {
    data object Idle : TranscriptState()
    data object Listening : TranscriptState()
    data class Partial(val text: String) : TranscriptState()
    data class Final(val text: String) : TranscriptState()
    data class Error(val cause: String) : TranscriptState()
}

interface SpeechTranscriber {
    val transcriptState: StateFlow<TranscriptState>
    fun startListening()
    fun stopListening()
    fun destroy()
}

/**
 * Fake SpeechTranscriber for unit tests. Exposes emit* methods to drive state
 * transitions manually from test code.
 */
class FakeSpeechTranscriber : SpeechTranscriber {
    private val _transcriptState = MutableStateFlow<TranscriptState>(TranscriptState.Idle)
    override val transcriptState: StateFlow<TranscriptState> = _transcriptState.asStateFlow()

    override fun startListening() {
        _transcriptState.value = TranscriptState.Listening
    }

    override fun stopListening() {
        _transcriptState.value = TranscriptState.Idle
    }

    override fun destroy() {
        _transcriptState.value = TranscriptState.Idle
    }

    fun emitPartial(text: String) {
        _transcriptState.value = TranscriptState.Partial(text)
    }

    fun emitFinal(text: String) {
        _transcriptState.value = TranscriptState.Final(text)
    }

    fun emitError(cause: String) {
        _transcriptState.value = TranscriptState.Error(cause)
    }
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.SpeechTranscriberTest"
```
Expected: All 6 tests PASS.

**Step 5: Verify existing tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All 18 tests PASS (12 existing + 6 new).

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/voice/SpeechTranscriber.kt app/src/test/kotlin/com/vela/app/voice/SpeechTranscriberTest.kt && git commit -m "feat: add TranscriptState sealed class, SpeechTranscriber interface, and FakeSpeechTranscriber"
```

---

## Task 3: Implement AndroidSpeechTranscriber

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/voice/AndroidSpeechTranscriber.kt`

No new test file for this task — `AndroidSpeechTranscriber` wraps `android.speech.SpeechRecognizer` which is not unit-testable on JVM. It will be tested via instrumented tests in Task 13. The `FakeSpeechTranscriber` from Task 2 is used everywhere in unit tests instead.

**Step 1: Create `app/src/main/kotlin/com/vela/app/voice/AndroidSpeechTranscriber.kt`**

```kotlin
package com.vela.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production SpeechTranscriber backed by Android SpeechRecognizer.
 *
 * Must be created on the main thread (SpeechRecognizer requirement).
 * Prefers offline recognition when available.
 */
class AndroidSpeechTranscriber(
    private val context: Context,
) : SpeechTranscriber {

    private val _transcriptState = MutableStateFlow<TranscriptState>(TranscriptState.Idle)
    override val transcriptState: StateFlow<TranscriptState> = _transcriptState.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    private val recognitionIntent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _transcriptState.value = TranscriptState.Listening
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Unknown error ($error)"
            }
            _transcriptState.value = TranscriptState.Error(message)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            _transcriptState.value = if (text.isNotEmpty()) {
                TranscriptState.Final(text)
            } else {
                TranscriptState.Error("No speech recognized")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                _transcriptState.value = TranscriptState.Partial(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun startListening() {
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(listener)
        _transcriptState.value = TranscriptState.Listening
        rec.startListening(recognitionIntent)
    }

    override fun stopListening() {
        recognizer?.stopListening()
        _transcriptState.value = TranscriptState.Idle
    }

    override fun destroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _transcriptState.value = TranscriptState.Idle
    }
}
```

**Step 2: Verify the build compiles**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 3: Verify all existing tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All 18 tests PASS (no regressions).

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/voice/AndroidSpeechTranscriber.kt && git commit -m "feat: implement AndroidSpeechTranscriber wrapping SpeechRecognizer"
```

---

## Task 4: Update VoiceCapture to Delegate to SpeechTranscriber

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt`
- Modify: `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt`

Phase 1's `VoiceCapture` wrote raw audio to WAV files via `AudioRecordWrapper`. Phase 2 no longer needs raw audio — `SpeechTranscriber` handles everything. We refactor `VoiceCapture` to delegate to a `SpeechTranscriber` and expose `TranscriptState` instead of `isRecording: Boolean`.

**Step 1: Update the test to use the new API**

Replace the entire contents of `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt` with:

```kotlin
package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceCaptureTest {

    @Test
    fun initialStateIsIdle() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun startCaptureTransitionsToListening() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        voiceCapture.startCapture()
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Listening)
    }

    @Test
    fun stopCaptureTransitionsToIdle() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        voiceCapture.startCapture()
        voiceCapture.stopCapture()
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun transcriptStateForwardsFinal() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        voiceCapture.startCapture()
        transcriber.emitFinal("hello world")
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Final("hello world"))
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest"
```
Expected: Compilation fails. `VoiceCapture` constructor still expects `outputDir` and `audioRecordFactory`, not a `SpeechTranscriber`.

**Step 3: Rewrite VoiceCapture to delegate to SpeechTranscriber**

Replace the entire contents of `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt` with:

```kotlin
package com.vela.app.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Facade over SpeechTranscriber for use by the UI layer.
 *
 * Phase 1 used AudioRecordWrapper for raw audio capture.
 * Phase 2 delegates entirely to SpeechTranscriber for real-time transcription.
 * The AudioRecordWrapper interface is preserved in this file for backward
 * compatibility but is no longer used by VoiceCapture.
 */
class VoiceCapture(
    private val transcriber: SpeechTranscriber,
) {
    val transcriptState: StateFlow<TranscriptState>
        get() = transcriber.transcriptState

    fun startCapture() {
        transcriber.startListening()
    }

    fun stopCapture() {
        transcriber.stopListening()
    }

    fun destroy() {
        transcriber.destroy()
    }
}

/**
 * Retained from Phase 1 for backward compatibility.
 * No longer used by VoiceCapture but may be used by other code.
 */
interface AudioRecordWrapper {
    fun startRecording()
    fun stop()
    fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun release()
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest"
```
Expected: All 4 tests PASS.

**Step 5: Fix ConversationScreen.kt compilation**

`ConversationScreen.kt` currently creates `VoiceCapture` with the old constructor. The `ConversationScreen` will be fully rewritten in Task 12, but it must compile now. Replace the `voiceCapture` creation block and its usages.

Replace the entire contents of `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` with:

```kotlin
package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.VoiceButton
import com.vela.app.voice.FakeSpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture

@Composable
fun ConversationScreen(viewModel: ConversationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    // Phase 2 interim: use FakeSpeechTranscriber until DI wiring in Task 11
    val voiceCapture = remember { VoiceCapture(FakeSpeechTranscriber()) }
    val transcriptState by voiceCapture.transcriptState.collectAsState()
    val isListening = transcriptState is TranscriptState.Listening
            || transcriptState is TranscriptState.Partial

    DisposableEffect(Unit) {
        onDispose { voiceCapture.destroy() }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) voiceCapture.startCapture()
    }

    Scaffold(
        floatingActionButton = {
            VoiceButton(
                isListening = isListening,
                onToggle = {
                    if (isListening) {
                        voiceCapture.stopCapture()
                        // Phase 2 interim: no transcript to forward yet
                    } else {
                        if (hasPermission) {
                            voiceCapture.startCapture()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (messages.isEmpty() && !isProcessing) {
                Text(
                    text = "Tap the microphone to start",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages) { message ->
                        MessageBubble(message = message)
                    }
                }
            }
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = message.content,
            modifier = Modifier
                .background(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        )
    }
}
```

**Step 6: Verify the full build and all unit tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. All 18 unit tests PASS (12 original Phase 1 count minus 4 old VoiceCaptureTest + 4 new VoiceCaptureTest + 6 SpeechTranscriberTest = 18).

**Step 7: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt && git commit -m "refactor: VoiceCapture delegates to SpeechTranscriber instead of AudioRecordWrapper"
```

---

## Task 5: Implement TtsEngine

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/audio/TtsEngine.kt`
- Create: `app/src/test/kotlin/com/vela/app/audio/TtsEngineTest.kt`

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/audio/TtsEngineTest.kt`:

```kotlin
package com.vela.app.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsEngineTest {

    @Test
    fun speakRecordsText() = runTest {
        val engine = FakeTtsEngine()
        engine.speak("hello world")
        assertThat(engine.spokenTexts).containsExactly("hello world")
    }

    @Test
    fun speakMultipleTextsRecordsAll() = runTest {
        val engine = FakeTtsEngine()
        engine.speak("first")
        engine.speak("second")
        assertThat(engine.spokenTexts).containsExactly("first", "second").inOrder()
    }

    @Test
    fun stopClearsCurrentSpeech() {
        val engine = FakeTtsEngine()
        engine.stop()
        assertThat(engine.stopCount).isEqualTo(1)
    }

    @Test
    fun shutdownCallsShutdown() {
        val engine = FakeTtsEngine()
        engine.shutdown()
        assertThat(engine.isShutdown).isTrue()
    }

    @Test
    fun implementsTtsEngineInterface() {
        val engine: TtsEngine = FakeTtsEngine()
        assertThat(engine).isInstanceOf(TtsEngine::class.java)
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.audio.TtsEngineTest"
```
Expected: Compilation fails with `Unresolved reference: TtsEngine`, `Unresolved reference: FakeTtsEngine`.

**Step 3: Create TtsEngine interface, AndroidTtsEngine, and FakeTtsEngine**

Create `app/src/main/kotlin/com/vela/app/audio/TtsEngine.kt`:

```kotlin
package com.vela.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

interface TtsEngine {
    suspend fun speak(text: String)
    fun stop()
    fun shutdown()
}

/**
 * Production TTS backed by Android TextToSpeech.
 *
 * Initialization is asynchronous — the first call to speak() will wait
 * until the engine is ready.
 */
class AndroidTtsEngine(context: Context) : TtsEngine {

    @Volatile
    private var initialized = false

    @Volatile
    private var initFailed = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            initialized = true
        } else {
            initFailed = true
        }
    }

    override suspend fun speak(text: String) {
        // Wait for initialization (up to ~5s, then give up)
        var waited = 0
        while (!initialized && !initFailed && waited < 5000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        if (!initialized) return

        tts.language = Locale.US

        suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) cont.resume(Unit)
                }
                @Deprecated("Deprecated in API")
                override fun onError(id: String?) {
                    if (id == utteranceId) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

/**
 * Fake TTS for unit tests. Records all spoken text and call counts.
 */
class FakeTtsEngine : TtsEngine {
    val spokenTexts = mutableListOf<String>()
    var stopCount = 0
        private set
    var isShutdown = false
        private set

    override suspend fun speak(text: String) {
        spokenTexts.add(text)
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        isShutdown = true
    }
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.audio.TtsEngineTest"
```
Expected: All 5 tests PASS.

**Step 5: Verify all existing tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All 23 tests PASS (18 + 5 new).

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/audio/ app/src/test/kotlin/com/vela/app/audio/ && git commit -m "feat: add TtsEngine interface, AndroidTtsEngine, and FakeTtsEngine"
```

---

## Task 6: Implement ModelManager

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ai/ModelManager.kt`
- Create: `app/src/test/kotlin/com/vela/app/ai/ModelManagerTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Add the model URL placeholder to strings.xml**

Replace the entire contents of `app/src/main/res/values/strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Vela</string>
    <!-- TODO: Set model_download_url to the real Kaggle/Google AI Hub URL for Gemma 3 1B Q4 -->
    <string name="model_download_url">https://example.com/models/vela-model.bin</string>
</resources>
```

**Step 2: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/ai/ModelManagerTest.kt`:

```kotlin
package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun initialStateIsNotDownloaded() {
        val manager = ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model.bin").toString(),
        )
        assertThat(manager.downloadState.value).isEqualTo(DownloadState.NotDownloaded)
    }

    @Test
    fun downloadSucceedsAndReportsDownloaded() = runTest {
        val fakeModel = ByteArray(1024) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(fakeModel))
                .setHeader("Content-Length", fakeModel.size.toString()),
        )

        val manager = ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model.bin").toString(),
        )
        manager.downloadModel()

        val state = manager.downloadState.value
        assertThat(state).isInstanceOf(DownloadState.Downloaded::class.java)
        val downloaded = state as DownloadState.Downloaded
        assertThat(java.io.File(downloaded.path).exists()).isTrue()
        assertThat(java.io.File(downloaded.path).length()).isEqualTo(1024L)
    }

    @Test
    fun downloadReportsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val manager = ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model.bin").toString(),
        )
        manager.downloadModel()

        val state = manager.downloadState.value
        assertThat(state).isInstanceOf(DownloadState.Error::class.java)
    }

    @Test
    fun checkExistingModelReportsDownloaded() {
        val modelFile = java.io.File(tempFolder.root, "vela-model.bin")
        modelFile.writeBytes(ByteArray(100))

        val manager = ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model.bin").toString(),
        )
        manager.checkExistingModel()

        val state = manager.downloadState.value
        assertThat(state).isInstanceOf(DownloadState.Downloaded::class.java)
    }

    @Test
    fun checkExistingModelWhenAbsentRemainsNotDownloaded() {
        val manager = ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model.bin").toString(),
        )
        manager.checkExistingModel()

        assertThat(manager.downloadState.value).isEqualTo(DownloadState.NotDownloaded)
    }
}
```

**Step 3: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.ModelManagerTest"
```
Expected: Compilation fails with `Unresolved reference: ModelManager`, `Unresolved reference: DownloadState`.

**Step 4: Implement ModelManager**

Create `app/src/main/kotlin/com/vela/app/ai/ModelManager.kt`:

```kotlin
package com.vela.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data class Downloading(val percent: Int) : DownloadState()
    data class Downloaded(val path: String) : DownloadState()
    data class Error(val cause: String) : DownloadState()
}

/**
 * Manages download and local storage of the Gemma model file.
 *
 * Model is stored at [modelsDir]/vela-model.bin.
 * Downloads from [modelUrl] using OkHttp, reporting progress via [downloadState].
 */
class ModelManager(
    private val modelsDir: File,
    private val modelUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    companion object {
        const val MODEL_FILENAME = "vela-model.bin"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    val modelPath: String
        get() = File(modelsDir, MODEL_FILENAME).absolutePath

    /**
     * Check if the model file already exists on disk.
     * Updates [downloadState] to [DownloadState.Downloaded] if found.
     */
    fun checkExistingModel() {
        val file = File(modelsDir, MODEL_FILENAME)
        if (file.exists() && file.length() > 0) {
            _downloadState.value = DownloadState.Downloaded(file.absolutePath)
        }
    }

    /**
     * Download the model from [modelUrl]. Reports progress via [downloadState].
     * On success, transitions to [DownloadState.Downloaded].
     * On failure, transitions to [DownloadState.Error].
     */
    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val targetFile = File(modelsDir, MODEL_FILENAME)
        val tempFile = File(modelsDir, "$MODEL_FILENAME.tmp")

        _downloadState.value = DownloadState.Downloading(0)

        try {
            val request = Request.Builder().url(modelUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _downloadState.value = DownloadState.Error("HTTP ${response.code}")
                return@withContext
            }

            val body = response.body ?: run {
                _downloadState.value = DownloadState.Error("Empty response body")
                return@withContext
            }

            val contentLength = body.contentLength()
            var totalBytesRead = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt()
                            _downloadState.value = DownloadState.Downloading(percent.coerceAtMost(100))
                        }
                    }
                }
            }

            tempFile.renameTo(targetFile)
            _downloadState.value = DownloadState.Downloaded(targetFile.absolutePath)
        } catch (e: Exception) {
            tempFile.delete()
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
        }
    }
}
```

**Step 5: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.ModelManagerTest"
```
Expected: All 5 tests PASS.

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ai/ModelManager.kt app/src/test/kotlin/com/vela/app/ai/ModelManagerTest.kt app/src/main/res/values/strings.xml && git commit -m "feat: add ModelManager with download, progress tracking, and file validation"
```

---

## Task 7: Implement MediaPipeGemmaEngine

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ai/MediaPipeGemmaEngine.kt`
- Create: `app/src/test/kotlin/com/vela/app/ai/MediaPipeGemmaEngineTest.kt`

The real `LlmInference` from MediaPipe requires native libraries and a model file, so it can't be unit-tested on JVM. We introduce an `LlmInferenceWrapper` interface to make the engine testable.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/ai/MediaPipeGemmaEngineTest.kt`:

```kotlin
package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPipeGemmaEngineTest {

    @Test
    fun processTextReturnsResponse() = runTest {
        val fakeInference = FakeLlmInferenceWrapper("This is the answer.")
        val engine = MediaPipeGemmaEngine(fakeInference)
        val result = engine.processText("What is 2+2?")
        assertThat(result).isEqualTo("This is the answer.")
    }

    @Test
    fun processTextPassesInputToInference() = runTest {
        val fakeInference = FakeLlmInferenceWrapper("response")
        val engine = MediaPipeGemmaEngine(fakeInference)
        engine.processText("hello there")
        assertThat(fakeInference.lastInput).isEqualTo("hello there")
    }

    @Test(expected = IllegalStateException::class)
    fun processTextThrowsWhenInferenceIsNull() = runTest {
        val engine = MediaPipeGemmaEngine(inference = null)
        engine.processText("hello")
    }

    @Test
    fun implementsGemmaEngineInterface() {
        val engine: GemmaEngine = MediaPipeGemmaEngine(FakeLlmInferenceWrapper("x"))
        assertThat(engine).isInstanceOf(GemmaEngine::class.java)
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.MediaPipeGemmaEngineTest"
```
Expected: Compilation fails with `Unresolved reference: MediaPipeGemmaEngine`, `Unresolved reference: FakeLlmInferenceWrapper`.

**Step 3: Implement MediaPipeGemmaEngine with wrapper interface**

Create `app/src/main/kotlin/com/vela/app/ai/MediaPipeGemmaEngine.kt`:

```kotlin
package com.vela.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction over MediaPipe LlmInference for testability.
 * In production, wraps a real LlmInference instance.
 * In unit tests, replaced with FakeLlmInferenceWrapper.
 */
interface LlmInferenceWrapper {
    fun generateResponse(inputText: String): String
    fun close()
}

/**
 * Production wrapper around MediaPipe LlmInference.
 */
class RealLlmInferenceWrapper(
    context: Context,
    modelPath: String,
    maxTokens: Int = 512,
    temperature: Float = 0.7f,
    topK: Int = 40,
) : LlmInferenceWrapper {

    private val inference: LlmInference

    init {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTemperature(temperature)
            .setTopK(topK)
            .build()
        inference = LlmInference.createFromOptions(context, options)
    }

    override fun generateResponse(inputText: String): String {
        return inference.generateResponse(inputText)
    }

    override fun close() {
        inference.close()
    }
}

/**
 * Fake inference for unit tests.
 */
class FakeLlmInferenceWrapper(
    private val response: String,
) : LlmInferenceWrapper {
    var lastInput: String? = null
        private set

    override fun generateResponse(inputText: String): String {
        lastInput = inputText
        return response
    }

    override fun close() {}
}

/**
 * GemmaEngine backed by MediaPipe Tasks GenAI LlmInference.
 *
 * Accepts a nullable [LlmInferenceWrapper] — null means the model is not loaded.
 * Calling [processText] on an unloaded engine throws [IllegalStateException].
 */
class MediaPipeGemmaEngine(
    private val inference: LlmInferenceWrapper?,
) : GemmaEngine {

    override suspend fun processText(input: String): String = withContext(Dispatchers.IO) {
        val inf = inference ?: throw IllegalStateException("Model not loaded")
        inf.generateResponse(input)
    }
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.MediaPipeGemmaEngineTest"
```
Expected: All 4 tests PASS.

**Step 5: Verify all existing tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All 27 tests PASS.

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ai/MediaPipeGemmaEngine.kt app/src/test/kotlin/com/vela/app/ai/MediaPipeGemmaEngineTest.kt && git commit -m "feat: add MediaPipeGemmaEngine with LlmInferenceWrapper abstraction"
```

---

## Task 8: Implement IntentExtractor

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ai/IntentExtractor.kt`
- Create: `app/src/test/kotlin/com/vela/app/ai/IntentExtractorTest.kt`

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/vela/app/ai/IntentExtractorTest.kt`:

```kotlin
package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntentExtractorTest {

    @Test
    fun extractParsesValidJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine(
            """{"action":"question","target":"weather","constraints":[],"rawText":"what is the weather"}"""
        )
        val extractor = IntentExtractor(engine)
        val intent = extractor.extract("what is the weather")

        assertThat(intent.action).isEqualTo("question")
        assertThat(intent.target).isEqualTo("weather")
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("what is the weather")
    }

    @Test
    fun extractParsesJsonWithConstraints() = runTest {
        val engine = ConfigurableFakeGemmaEngine(
            """{"action":"reminder","target":"groceries","constraints":["before 5pm","at Costco"],"rawText":"remind me to get groceries"}"""
        )
        val extractor = IntentExtractor(engine)
        val intent = extractor.extract("remind me to get groceries")

        assertThat(intent.action).isEqualTo("reminder")
        assertThat(intent.target).isEqualTo("groceries")
        assertThat(intent.constraints).containsExactly("before 5pm", "at Costco")
    }

    @Test
    fun extractHandlesNullTarget() = runTest {
        val engine = ConfigurableFakeGemmaEngine(
            """{"action":"search","target":null,"constraints":[],"rawText":"search"}"""
        )
        val extractor = IntentExtractor(engine)
        val intent = extractor.extract("search")

        assertThat(intent.action).isEqualTo("search")
        assertThat(intent.target).isNull()
    }

    @Test
    fun extractFallsBackOnMalformedJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine("this is not json at all")
        val extractor = IntentExtractor(engine)
        val intent = extractor.extract("what time is it")

        assertThat(intent.action).isEqualTo("unknown")
        assertThat(intent.target).isNull()
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("what time is it")
    }

    @Test
    fun extractFallsBackOnPartialJson() = runTest {
        val engine = ConfigurableFakeGemmaEngine("""{"action":"search"}""")
        val extractor = IntentExtractor(engine)
        val intent = extractor.extract("find stuff")

        assertThat(intent.action).isEqualTo("search")
        assertThat(intent.target).isNull()
        assertThat(intent.constraints).isEmpty()
        assertThat(intent.rawText).isEqualTo("find stuff")
    }

    @Test
    fun extractSendsPromptToEngine() = runTest {
        val engine = ConfigurableFakeGemmaEngine(
            """{"action":"question","target":null,"constraints":[],"rawText":"hi"}"""
        )
        val extractor = IntentExtractor(engine)
        extractor.extract("hi")

        assertThat(engine.lastInput).contains("hi")
        assertThat(engine.lastInput).contains("Extract the user's intent")
    }
}

/**
 * A configurable FakeGemmaEngine that returns a specific response string
 * and records the last input it received.
 */
private class ConfigurableFakeGemmaEngine(
    private val response: String,
) : GemmaEngine {
    var lastInput: String? = null
        private set

    override suspend fun processText(input: String): String {
        lastInput = input
        return response
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.IntentExtractorTest"
```
Expected: Compilation fails with `Unresolved reference: IntentExtractor`, `Unresolved reference: VelaIntent`.

**Step 3: Implement IntentExtractor**

Create `app/src/main/kotlin/com/vela/app/ai/IntentExtractor.kt`:

```kotlin
package com.vela.app.ai

import org.json.JSONObject

/**
 * Structured intent extracted from user voice input by Gemma.
 */
data class VelaIntent(
    val action: String,
    val target: String?,
    val constraints: List<String>,
    val rawText: String,
)

/**
 * Extracts structured intent from user text via a GemmaEngine prompt.
 *
 * Sends a structured prompt asking Gemma to return JSON, then parses the result.
 * Falls back to VelaIntent(action="unknown") if Gemma returns invalid JSON.
 */
class IntentExtractor(private val engine: GemmaEngine) {

    suspend fun extract(userText: String): VelaIntent {
        val prompt = buildPrompt(userText)
        val response = engine.processText(prompt)
        return parseIntent(response, userText)
    }

    private fun buildPrompt(userText: String): String {
        return """
            Extract the user's intent from this voice input. Return JSON only.
            Format: {"action":"<action>","target":"<target or null>","constraints":["<constraint>",...],"rawText":"<original>"}
            
            Voice input: "$userText"
            
            JSON:
        """.trimIndent()
    }

    private fun parseIntent(response: String, originalText: String): VelaIntent {
        return try {
            // Find the JSON object in the response (Gemma may add extra text around it)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                return fallbackIntent(originalText)
            }

            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(jsonStr)

            VelaIntent(
                action = json.optString("action", "unknown"),
                target = if (json.isNull("target")) null else json.optString("target", null),
                constraints = parseConstraints(json),
                rawText = json.optString("rawText", originalText),
            )
        } catch (_: Exception) {
            fallbackIntent(originalText)
        }
    }

    private fun parseConstraints(json: JSONObject): List<String> {
        val array = json.optJSONArray("constraints") ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun fallbackIntent(originalText: String): VelaIntent {
        return VelaIntent(
            action = "unknown",
            target = null,
            constraints = emptyList(),
            rawText = originalText,
        )
    }
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.IntentExtractorTest"
```
Expected: All 6 tests PASS.

**Step 5: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ai/IntentExtractor.kt app/src/test/kotlin/com/vela/app/ai/IntentExtractorTest.kt && git commit -m "feat: add IntentExtractor with structured Gemma prompt and JSON parsing"
```

---

## Task 9: Add ModelLoadingScreen

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/loading/ModelLoadingScreen.kt`
- Create: `app/src/androidTest/kotlin/com/vela/app/ModelLoadingScreenTest.kt`

**Step 1: Write the failing instrumented test**

Create `app/src/androidTest/kotlin/com/vela/app/ModelLoadingScreenTest.kt`:

```kotlin
package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.app.ai.DownloadState
import com.vela.app.ui.loading.ModelLoadingScreen
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelLoadingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsDownloadButton() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.NotDownloaded,
                    onDownloadClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasContentDesc("Download AI model")
    }

    @Test
    fun showsProgressDuringDownload() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Downloading(42),
                    onDownloadClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasContentDesc("Model download progress")
    }

    @Test
    fun showsCompletionWhenDownloaded() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Downloaded("/path/to/model"),
                    onDownloadClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasText("Model ready")
    }

    @Test
    fun showsErrorState() {
        composeTestRule.setContent {
            VelaTheme {
                ModelLoadingScreen(
                    downloadState = DownloadState.Error("HTTP 404"),
                    onDownloadClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasText("Download failed")
        AccessibilitySnapshot.assertHasContentDesc("Download AI model")
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebugAndroidTest
```
Expected: Compilation fails with `Unresolved reference: ModelLoadingScreen`.

**Step 3: Implement ModelLoadingScreen**

Create `app/src/main/kotlin/com/vela/app/ui/loading/ModelLoadingScreen.kt`:

```kotlin
package com.vela.app.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vela.app.ai.DownloadState

@Composable
fun ModelLoadingScreen(
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (downloadState) {
            is DownloadState.NotDownloaded -> {
                Text(
                    text = "Vela needs an AI model to work",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Download the on-device model (~600 MB) to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.semantics {
                        contentDescription = "Download AI model"
                    },
                ) {
                    Text("Download AI Model")
                }
            }

            is DownloadState.Downloading -> {
                CircularProgressIndicator(
                    progress = { downloadState.percent / 100f },
                    modifier = Modifier
                        .size(64.dp)
                        .semantics {
                            contentDescription = "Model download progress"
                        },
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Downloading model... ${downloadState.percent}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is DownloadState.Downloaded -> {
                Text(
                    text = "Model ready",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your AI assistant is ready to use.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            is DownloadState.Error -> {
                Text(
                    text = "Download failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = downloadState.cause,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.semantics {
                        contentDescription = "Download AI model"
                    },
                ) {
                    Text("Retry Download")
                }
            }
        }
    }
}
```

**Step 4: Verify the app builds**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug && ./gradlew :app:assembleDebugAndroidTest
```
Expected: BUILD SUCCESSFUL for both.

**Step 5: Commit**

Note: The instrumented tests in this task use `createComposeRule` which runs on a device. If no emulator is available, verify compilation succeeds. The tests will be validated end-to-end in Task 15.

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ui/loading/ app/src/androidTest/kotlin/com/vela/app/ModelLoadingScreenTest.kt && git commit -m "feat: add ModelLoadingScreen with download progress and accessibility semantics"
```

---

## Task 10: Update ConversationViewModel — Full Flow

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`
- Modify: `app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt`

**Step 1: Write the updated test with full flow coverage**

Replace the entire contents of `app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt` with:

```kotlin
package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class FakeConversationRepository : ConversationRepository {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessages(): Flow<List<Message>> = _messages

    override suspend fun saveMessage(message: Message) {
        _messages.value = _messages.value + message
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeConversationRepository
    private lateinit var fakeTts: FakeTtsEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeConversationRepository()
        fakeTts = FakeTtsEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        engine: GemmaEngine = FakeGemmaEngine(),
    ): ConversationViewModel {
        return ConversationViewModel(
            gemmaEngine = engine,
            repository = fakeRepository,
            intentExtractor = IntentExtractor(engine),
            ttsEngine = fakeTts,
        )
    }

    @Test
    fun initialMessagesListIsEmpty() {
        val viewModel = createViewModel()
        assertThat(viewModel.messages.value).isEmpty()
    }

    @Test
    fun onVoiceInputAddsUserMessage() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("what time is it")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).isNotEmpty()
        assertThat(viewModel.messages.value.first().role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value.first().content).isEqualTo("what time is it")
    }

    @Test
    fun onVoiceInputTriggersAssistantResponse() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("hello")
        advanceUntilIdle()
        assertThat(viewModel.messages.value).hasSize(2)
        assertThat(viewModel.messages.value[0].role).isEqualTo(MessageRole.USER)
        assertThat(viewModel.messages.value[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(viewModel.messages.value[1].content).isNotEmpty()
    }

    @Test
    fun isProcessingIsTrueWhileGemmaRuns() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("hello")
        advanceTimeBy(50)
        assertThat(viewModel.isProcessing.value).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.isProcessing.value).isFalse()
    }

    @Test
    fun onVoiceInputTriggersTextToSpeech() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("hello")
        advanceUntilIdle()
        assertThat(fakeTts.spokenTexts).isNotEmpty()
    }

    @Test
    fun onVoiceInputWithTranscriptStoresOriginalText() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onVoiceInput("what is the weather today")
        advanceUntilIdle()
        assertThat(viewModel.messages.value.first().content).isEqualTo("what is the weather today")
    }

    @Test
    fun engineStateIsModelReadyByDefault() {
        val viewModel = createViewModel()
        assertThat(viewModel.engineState.value).isEqualTo(EngineState.ModelReady)
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ui.conversation.ConversationViewModelTest"
```
Expected: Compilation fails because `ConversationViewModel` constructor does not accept `intentExtractor` or `ttsEngine` yet, and `EngineState` does not exist.

**Step 3: Rewrite ConversationViewModel with full flow**

Replace the entire contents of `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt` with:

```kotlin
package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.TtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EngineState {
    ModelNotReady,
    ModelReady,
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val gemmaEngine: GemmaEngine,
    private val repository: ConversationRepository,
    private val intentExtractor: IntentExtractor,
    private val ttsEngine: TtsEngine,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.ModelReady)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMessages().collect { messages ->
                _messages.value = messages
            }
        }
    }

    /**
     * Update engine state. Called by the DI layer or UI when model availability changes.
     */
    fun setEngineState(state: EngineState) {
        _engineState.value = state
    }

    /**
     * Called when voice transcription completes.
     * Full pipeline: save user message → extract intent → process with Gemma → TTS → save response.
     *
     * @param transcript The transcribed text from SpeechTranscriber.
     */
    fun onVoiceInput(transcript: String) {
        viewModelScope.launch {
            // Save user message
            val userMessage = Message(
                role = MessageRole.USER,
                content = transcript,
            )
            repository.saveMessage(userMessage)

            // Extract intent and process through Gemma
            _isProcessing.value = true
            try {
                val intent = intentExtractor.extract(transcript)
                val prompt = buildEnginePrompt(intent.action, intent.target, transcript)
                val response = gemmaEngine.processText(prompt)

                // Speak the response via TTS
                ttsEngine.speak(response)

                // Save assistant message
                val assistantMessage = Message(
                    role = MessageRole.ASSISTANT,
                    content = response,
                )
                repository.saveMessage(assistantMessage)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun buildEnginePrompt(action: String, target: String?, transcript: String): String {
        return buildString {
            append("User intent: action=$action")
            if (target != null) append(", target=$target")
            append("\nOriginal request: $transcript")
            append("\n\nProvide a helpful, concise response.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ui.conversation.ConversationViewModelTest"
```
Expected: All 7 tests PASS.

**Step 5: Verify all unit tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All tests PASS (previous 29 minus 4 old ViewModel tests + 7 new = 32).

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt && git commit -m "feat: ConversationViewModel full flow — IntentExtractor + GemmaEngine + TTS"
```

---

## Task 11: Update AppModule for Real Engine Wiring

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

**Step 1: Rewrite AppModule with real engine providers and fallback logic**

Replace the entire contents of `app/src/main/kotlin/com/vela/app/di/AppModule.kt` with:

```kotlin
package com.vela.app.di

import android.content.Context
import androidx.room.Room
import com.vela.app.ai.DownloadState
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.ai.MediaPipeGemmaEngine
import com.vela.app.ai.ModelManager
import com.vela.app.ai.RealLlmInferenceWrapper
import com.vela.app.audio.AndroidTtsEngine
import com.vela.app.audio.TtsEngine
import com.vela.app.data.db.MessageDao
import com.vela.app.data.db.VelaDatabase
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.data.repository.RoomConversationRepository
import com.vela.app.voice.AndroidSpeechTranscriber
import com.vela.app.voice.SpeechTranscriber
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVelaDatabase(@ApplicationContext context: Context): VelaDatabase =
        Room.databaseBuilder(context, VelaDatabase::class.java, "vela_database").build()

    @Provides
    fun provideMessageDao(database: VelaDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideConversationRepository(messageDao: MessageDao): ConversationRepository =
        RoomConversationRepository(messageDao)

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        val modelsDir = File(context.filesDir, "models")
        val modelUrl = context.getString(com.vela.app.R.string.model_download_url)
        return ModelManager(modelsDir = modelsDir, modelUrl = modelUrl).also {
            it.checkExistingModel()
        }
    }

    @Provides
    @Singleton
    fun provideGemmaEngine(
        @ApplicationContext context: Context,
        modelManager: ModelManager,
    ): GemmaEngine {
        val state = modelManager.downloadState.value
        return if (state is DownloadState.Downloaded) {
            try {
                val wrapper = RealLlmInferenceWrapper(context, state.path)
                MediaPipeGemmaEngine(wrapper)
            } catch (_: Exception) {
                // Fall back to fake if model loading fails (e.g., corrupted file)
                FakeGemmaEngine()
            }
        } else {
            FakeGemmaEngine()
        }
    }

    @Provides
    @Singleton
    fun provideIntentExtractor(engine: GemmaEngine): IntentExtractor =
        IntentExtractor(engine)

    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine =
        AndroidTtsEngine(context)

    @Provides
    @Singleton
    fun provideSpeechTranscriber(@ApplicationContext context: Context): SpeechTranscriber =
        AndroidSpeechTranscriber(context)
}
```

**Step 2: Verify the build compiles**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. The Hilt DI graph resolves. Without a real model file, `FakeGemmaEngine` is provided.

**Step 3: Verify all unit tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All tests PASS. Unit tests don't use Hilt — they construct classes directly.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/di/AppModule.kt && git commit -m "feat: AppModule wires real MediaPipe engine when model present, FakeGemmaEngine fallback"
```

---

## Task 12: Update ConversationScreen — Model-Not-Ready State

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

**Step 1: Rewrite ConversationScreen with model state awareness and SpeechTranscriber integration**

Replace the entire contents of `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` with:

```kotlin
package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.VoiceButton
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import com.vela.app.voice.VoiceCapture

@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel(),
    speechTranscriber: SpeechTranscriber? = null,
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    // Use injected transcriber if provided (for tests), otherwise not available
    // In production, SpeechTranscriber is injected via Hilt and passed through MainActivity
    val voiceCapture = remember(speechTranscriber) {
        speechTranscriber?.let { VoiceCapture(it) }
    }
    val transcriptState = voiceCapture?.transcriptState?.collectAsState()
    val currentTranscriptState = transcriptState?.value ?: TranscriptState.Idle
    val isListening = currentTranscriptState is TranscriptState.Listening
            || currentTranscriptState is TranscriptState.Partial

    // When a final transcript arrives, forward it to the ViewModel
    LaunchedEffect(currentTranscriptState) {
        if (currentTranscriptState is TranscriptState.Final) {
            viewModel.onVoiceInput(currentTranscriptState.text)
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceCapture?.destroy() }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) voiceCapture?.startCapture()
    }

    when (engineState) {
        EngineState.ModelNotReady -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Download AI model to begin",
                    modifier = Modifier.semantics {
                        contentDescription = "Model download required"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { /* Navigate to ModelLoadingScreen */ },
                    modifier = Modifier.semantics {
                        contentDescription = "Download model"
                    },
                ) {
                    Text("Download Model")
                }
            }
        }

        EngineState.ModelReady -> {
            Scaffold(
                floatingActionButton = {
                    if (voiceCapture != null) {
                        VoiceButton(
                            isListening = isListening,
                            onToggle = {
                                if (isListening) {
                                    voiceCapture.stopCapture()
                                } else {
                                    if (hasPermission) {
                                        voiceCapture.startCapture()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                        )
                    } else {
                        VoiceButton(
                            isListening = false,
                            onToggle = {
                                // SpeechTranscriber not available — no-op
                            },
                        )
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    if (messages.isEmpty() && !isProcessing) {
                        Text(
                            text = "Tap the microphone to start",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages) { message ->
                                MessageBubble(message = message)
                            }
                        }
                    }
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = message.content,
            modifier = Modifier
                .background(
                    color = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        )
    }
}
```

**Step 2: Verify the build compiles**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 3: Verify all unit tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All tests PASS. The ConversationScreen changes are Compose-only and don't affect unit tests.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt && git commit -m "feat: ConversationScreen shows model-not-ready state and wires SpeechTranscriber"
```

---

## Task 13: End-to-End Instrumented Test

**Files:**
- Create: `app/src/androidTest/kotlin/com/vela/app/ConversationFlowTest.kt`

This test exercises the full pipeline: voice → STT → IntentExtractor → GemmaEngine → TTS + UI. All with fakes.

**Step 1: Write the instrumented test**

Create `app/src/androidTest/kotlin/com/vela/app/ConversationFlowTest.kt`:

```kotlin
package com.vela.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.FakeTtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.ui.conversation.ConversationScreen
import com.vela.app.ui.conversation.ConversationViewModel
import com.vela.app.ui.conversation.EngineState
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.util.AccessibilitySnapshot
import com.vela.app.voice.FakeSpeechTranscriber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun modelNotReadyStateShowsDownloadPrompt() {
        val fakeEngine = FakeGemmaEngine()
        val fakeRepo = InMemoryConversationRepository()
        val fakeTts = FakeTtsEngine()
        val viewModel = ConversationViewModel(
            gemmaEngine = fakeEngine,
            repository = fakeRepo,
            intentExtractor = IntentExtractor(fakeEngine),
            ttsEngine = fakeTts,
        )
        viewModel.setEngineState(EngineState.ModelNotReady)

        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    viewModel = viewModel,
                    speechTranscriber = FakeSpeechTranscriber(),
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasContentDesc("Model download required")
        AccessibilitySnapshot.assertHasContentDesc("Download model")
    }

    @Test
    fun modelReadyStateShowsVoiceButton() {
        val fakeEngine = FakeGemmaEngine()
        val fakeRepo = InMemoryConversationRepository()
        val fakeTts = FakeTtsEngine()
        val viewModel = ConversationViewModel(
            gemmaEngine = fakeEngine,
            repository = fakeRepo,
            intentExtractor = IntentExtractor(fakeEngine),
            ttsEngine = fakeTts,
        )

        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    viewModel = viewModel,
                    speechTranscriber = FakeSpeechTranscriber(),
                )
            }
        }
        composeTestRule.waitForIdle()
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun fullConversationFlowShowsAssistantResponse() {
        val fakeEngine = FakeGemmaEngine()
        val fakeRepo = InMemoryConversationRepository()
        val fakeTts = FakeTtsEngine()
        val fakeTranscriber = FakeSpeechTranscriber()
        val viewModel = ConversationViewModel(
            gemmaEngine = fakeEngine,
            repository = fakeRepo,
            intentExtractor = IntentExtractor(fakeEngine),
            ttsEngine = fakeTts,
        )

        composeTestRule.setContent {
            VelaTheme {
                ConversationScreen(
                    viewModel = viewModel,
                    speechTranscriber = fakeTranscriber,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Simulate: transcriber emits final text → ViewModel processes it
        fakeTranscriber.emitFinal("what time is it")

        // Wait for processing — FakeGemmaEngine has a 100ms delay
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.messages.value.size >= 2
        }

        composeTestRule.waitForIdle()

        // Verify assistant response appears
        AccessibilitySnapshot.assertHasText(
            "Hello! I'm Vela, your on-device AI assistant. How can I help?"
        )

        // Verify TTS was invoked
        assert(fakeTts.spokenTexts.isNotEmpty()) {
            "TtsEngine.speak() was never called"
        }
    }
}

/**
 * In-memory ConversationRepository for instrumented tests.
 */
private class InMemoryConversationRepository : ConversationRepository {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessages(): Flow<List<Message>> = _messages

    override suspend fun saveMessage(message: Message) {
        _messages.value = _messages.value + message
    }
}
```

**Step 2: Verify compilation succeeds**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebugAndroidTest
```
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/androidTest/kotlin/com/vela/app/ConversationFlowTest.kt && git commit -m "test: add end-to-end ConversationFlowTest with fakes for full pipeline"
```

---

## Task 14: Smoke Test for Real MediaPipe

**Files:**
- Create: `app/src/androidTest/kotlin/com/vela/app/MediaPipeSmokeTest.kt`

This test is annotated with a custom `@Slow` annotation and uses `assumeTrue` to skip when the model file is absent. It only runs on a physical device with the model file manually placed.

**Step 1: Create the `@Slow` annotation**

Create `app/src/androidTest/kotlin/com/vela/app/Slow.kt`:

```kotlin
package com.vela.app

/**
 * Marks a test as slow-running. These tests are excluded from CI
 * and only run manually when specific conditions are met (e.g., model file present).
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Slow
```

**Step 2: Create the smoke test**

Create `app/src/androidTest/kotlin/com/vela/app/MediaPipeSmokeTest.kt`:

```kotlin
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
 * Smoke test for real MediaPipe Gemma inference.
 *
 * Skipped automatically if the model file does not exist at
 * filesDir/models/vela-model.bin. To run this test manually:
 *
 * 1. Download the Gemma 3 1B Q4 model
 * 2. Push it to the device: adb push gemma3-1b.bin /data/data/com.vela.app/files/models/vela-model.bin
 * 3. Run: ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.MediaPipeSmokeTest"
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

        // Skip this test if the model is not present
        assumeTrue(
            "Model file not found at ${modelFile.absolutePath}. " +
                    "Push the Gemma model to the device to enable this test.",
            modelFile.exists(),
        )
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
```

**Step 3: Verify compilation succeeds**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebugAndroidTest
```
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/androidTest/kotlin/com/vela/app/Slow.kt app/src/androidTest/kotlin/com/vela/app/MediaPipeSmokeTest.kt && git commit -m "test: add @Slow MediaPipe smoke test (skips when model absent)"
```

---

## Task 15: Phase 2 Completion — Full Verification

**Files:**
- No new files. This task runs full verification and makes a completion commit.

**Step 1: Run all unit tests**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All unit tests PASS. The full count should be approximately:
- `VoiceCaptureTest` — 4 tests
- `SpeechTranscriberTest` — 6 tests
- `FakeGemmaEngineTest` — 4 tests
- `MediaPipeGemmaEngineTest` — 4 tests
- `ModelManagerTest` — 5 tests
- `IntentExtractorTest` — 6 tests
- `TtsEngineTest` — 5 tests
- `ConversationViewModelTest` — 7 tests
- **Total: ~41 unit tests**

**Step 2: Run all instrumented tests**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest
```
Expected: All instrumented tests PASS. The `MediaPipeSmokeTest` should SKIP (model file absent). Approximate count:
- `VoiceButtonTest` — 3 tests
- `ConversationScreenTest` — 3 tests (may need adjustment if voice capture wiring changed — fix if needed)
- `ModelLoadingScreenTest` — 4 tests
- `ConversationFlowTest` — 3 tests
- `MediaPipeSmokeTest` — 1 test (SKIPPED via `assumeTrue`)
- **Total: ~14 instrumented tests (13 pass, 1 skip)**

If the `ConversationScreenTest` tests from Phase 1 fail because the voice capture wiring changed (VoiceCapture now requires a SpeechTranscriber), update them to pass a `FakeSpeechTranscriber` or adjust the test expectations. The key tests to preserve are `conversationScreenShowsVoiceButton` and `tappingVoiceButtonTogglesState`.

**Step 3: Build the APK**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

**Step 4: Fix any remaining issues**

If any tests fail:
1. Read the failure output carefully
2. Fix the **minimum** amount of code to make the test pass
3. Re-run the failing test before proceeding

**Step 5: Commit**

```bash
cd /Users/ken/workspace/vela && git add -A && git commit -m "feat: Phase 2 complete — real STT, MediaPipe Gemma, TTS, intent extraction"
```

---

## Summary of Phase 2 Deliverables

| # | Component | New Files | Tests |
|---|-----------|-----------|-------|
| 1 | MediaPipe + OkHttp deps | build.gradle.kts, libs.versions.toml | Build verification |
| 2 | TranscriptState + SpeechTranscriber | SpeechTranscriber.kt | 6 unit tests |
| 3 | AndroidSpeechTranscriber | AndroidSpeechTranscriber.kt | Build verification |
| 4 | VoiceCapture refactor | VoiceCapture.kt (modified) | 4 unit tests (rewritten) |
| 5 | TtsEngine | TtsEngine.kt | 5 unit tests |
| 6 | ModelManager | ModelManager.kt, strings.xml | 5 unit tests |
| 7 | MediaPipeGemmaEngine | MediaPipeGemmaEngine.kt | 4 unit tests |
| 8 | IntentExtractor | IntentExtractor.kt | 6 unit tests |
| 9 | ModelLoadingScreen | ModelLoadingScreen.kt | 4 instrumented tests |
| 10 | ConversationViewModel full flow | ConversationViewModel.kt (rewritten) | 7 unit tests |
| 11 | AppModule wiring | AppModule.kt (rewritten) | Build verification |
| 12 | ConversationScreen model state | ConversationScreen.kt (rewritten) | Build verification |
| 13 | End-to-end flow test | ConversationFlowTest.kt | 3 instrumented tests |
| 14 | MediaPipe smoke test | MediaPipeSmokeTest.kt, Slow.kt | 1 instrumented (skippable) |
| 15 | Full verification | — | All pass |

**Total new tests: ~41 unit + ~8 instrumented = ~49 new tests**
**Combined with Phase 1: ~41 unit + ~14 instrumented = ~55 total tests**

---

## Post-Phase 2 State

After Phase 2, the app:
- Transcribes voice via Android SpeechRecognizer (offline-preferred)
- Extracts structured intent via Gemma 3 prompt
- Generates responses via MediaPipe LlmInference (when model present)
- Speaks responses via Android TextToSpeech
- Persists conversation in Room
- Falls back to FakeGemmaEngine when model is absent
- Shows a model download screen when needed

**What is NOT done (deferred to Phase 3+):**
- Node discovery / A2UI protocol
- Rubric compilation beyond basic intent
- Multi-turn memory / vector store
- Authorization plane
- Real model URL configuration (Ken must set `model_download_url` in strings.xml)
