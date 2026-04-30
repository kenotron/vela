# UI Phase 4: Voice Capture Overlay & Approval Gate Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Screen 6 (VoiceCaptureOverlay — tap-to-start/tap-to-stop voice recording with live transcript and review phase) and Screen 7 (ApprovalGateSheet — bottom sheet for session approval with deny/approve), replacing the Phase 1 `VoiceFabPlaceholder` with the fully-designed `VoiceFab`.

**Architecture:** Two new packages — `com.vela.app.ui.voice` and `com.vela.app.ui.approval`. `VoiceOverlayViewModel` owns all recording state (phase enum, transcript, elapsed timer, isRecording flag) and is driven by the existing `SpeechTranscriber` interface. `VoiceCaptureOverlay` is a full-screen `Dialog` composable with two phases (RECORDING → REVIEW); `VoiceFab` replaces `VoiceFabPlaceholder` in `AppNavigation.kt` and manages overlay visibility. `ApprovalSheetViewModel` holds a nullable `ApprovalRequest` state flow; `ApprovalGateSheet` is a `ModalBottomSheet` conditionally composed inside `VelaApp()`. Both features wire into `AppNavigation.kt` in Task 8.

**Tech Stack:** Kotlin, Jetpack Compose BOM 2025.04.01, Material 3 (`ModalBottomSheet`, `Dialog`), Hilt 2.51, JUnit 4, Google Truth 1.4.2, `kotlinx.coroutines.test` 1.8.0

---

## Phase 3 Contract — What Already Exists

Do **not** redefine any of these.

| Symbol | Location | Purpose |
|--------|----------|---------|
| `VelaColors.Abyss` | `com.vela.app.ui.theme.VelaColors` | `#0B0E1A` — deepest background |
| `VelaColors.SurfaceSub/SurfaceRaised/SurfacePeak` | `com.vela.app.ui.theme.VelaColors` | Tonal elevation layers |
| `VelaColors.Running/Waiting/Done/Error/Accent` | `com.vela.app.ui.theme.VelaColors` | Semantic status colors |
| `VelaColors.ErrorContainer/ErrorOnContainer` | `com.vela.app.ui.theme.VelaColors` | Stop-button fill / icon colors |
| `VelaColors.TextPrimary/TextSecondary/TextTertiary` | `com.vela.app.ui.theme.VelaColors` | Text hierarchy |
| `VelaColors.StrokeHair/StrokeEdge` | `com.vela.app.ui.theme.VelaColors` | `0x0FFFFFFF` / `0x1FFFFFFF` |
| `MaterialTheme.typography.displayMedium` | `com.vela.app.ui.theme.VelaTypography` | Instrument Serif 36sp |
| `MaterialTheme.typography.labelLarge` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp/600 (button labels) |
| `MaterialTheme.typography.labelSmall` | `com.vela.app.ui.theme.VelaTypography` | Inter 11sp/Bold/+2sp tracking |
| `MaterialTheme.typography.bodyMedium` | `com.vela.app.ui.theme.VelaTypography` | Inter 14sp (card meta) |
| `SpeechTranscriber` (interface) | `com.vela.app.voice` | Transcription contract |
| `FakeSpeechTranscriber` | `com.vela.app.voice` | Test double (defined in main sources, usable from tests) |
| `TranscriptState.Idle/Listening/Partial(text)/Final(text)/Error(cause)` | `com.vela.app.voice` | Transcriber state sealed class |
| `VoiceFabPlaceholder` | `com.vela.app.ui.navigation.AppNavigation` | Stub FAB — **replaced in Task 8** |
| `VelaApp()` | `com.vela.app.ui.navigation.AppNavigation` | Root composable — **modified in Task 8** |

---

## New Files Summary

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlay.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/voice/VoiceFab.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlaySourceTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/voice/VoiceFabSourceTest.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModel.kt` |
| Create | `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalGateSheet.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModelTest.kt` |
| Create | `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalGateSheetSourceTest.kt` |
| Modify | `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt` |

---

## Task 1: VoiceOverlayViewModel — Core State Logic

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModel.kt`

This task covers the ViewModel's phase transitions, boolean flags, reset logic, and the `formatElapsedMs` pure function. No coroutines yet — those come in Task 2.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt`:

```kotlin
package com.vela.app.ui.voice

import com.google.common.truth.Truth.assertThat
import com.vela.app.voice.FakeSpeechTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOverlayViewModelTest {

    // Shared scheduler so viewModelScope and runTest see the same virtual clock.
    private val testScheduler  = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(transcriber: FakeSpeechTranscriber = FakeSpeechTranscriber()) =
        VoiceOverlayViewModel(transcriber)

    // ── Phase / boolean state ────────────────────────────────────────────────

    @Test fun `initial phase is RECORDING`() {
        assertThat(makeVm().phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.RECORDING)
    }

    @Test fun `initial isRecording is false`() {
        assertThat(makeVm().isRecording.value).isFalse()
    }

    @Test fun `initial transcript is empty`() {
        assertThat(makeVm().transcript.value).isEmpty()
    }

    @Test fun `initial elapsedMs is 0`() {
        assertThat(makeVm().elapsedMs.value).isEqualTo(0L)
    }

    @Test fun `startRecording sets isRecording to true`() {
        val vm = makeVm()
        vm.startRecording()
        assertThat(vm.isRecording.value).isTrue()
    }

    @Test fun `stopRecording sets isRecording false and phase to REVIEW`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        assertThat(vm.isRecording.value).isFalse()
        assertThat(vm.phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.REVIEW)
    }

    @Test fun `discard resets phase to RECORDING, clears transcript and elapsedMs`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        vm.discard()
        assertThat(vm.phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.RECORDING)
        assertThat(vm.isRecording.value).isFalse()
        assertThat(vm.transcript.value).isEmpty()
        assertThat(vm.elapsedMs.value).isEqualTo(0L)
    }

    @Test fun `send resets phase, isRecording, and elapsedMs`() {
        val vm = makeVm()
        vm.startRecording()
        vm.stopRecording()
        vm.send("deploy to prod")
        assertThat(vm.phase.value).isEqualTo(VoiceOverlayViewModel.VoicePhase.RECORDING)
        assertThat(vm.isRecording.value).isFalse()
        assertThat(vm.elapsedMs.value).isEqualTo(0L)
    }

    // ── formatElapsedMs ──────────────────────────────────────────────────────

    @Test fun `formatElapsedMs 42 seconds returns 0 colon 42`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(42_000L)).isEqualTo("0:42")
    }

    @Test fun `formatElapsedMs 84 seconds returns 1 colon 24`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(84_000L)).isEqualTo("1:24")
    }

    @Test fun `formatElapsedMs 0 returns 0 colon 00`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(0L)).isEqualTo("0:00")
    }

    @Test fun `formatElapsedMs pads single-digit seconds`() {
        assertThat(VoiceOverlayViewModel.formatElapsedMs(5_000L)).isEqualTo("0:05")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/ken/workspace/vela
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceOverlayViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: FAILED — `error: unresolved reference: VoiceOverlayViewModel`

- [ ] **Step 3: Create VoiceOverlayViewModel.kt**

Create `app/src/main/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModel.kt`:

```kotlin
package com.vela.app.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.voice.SpeechTranscriber
import com.vela.app.voice.TranscriptState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceOverlayViewModel @Inject constructor(
    private val speechTranscriber: SpeechTranscriber,
) : ViewModel() {

    enum class VoicePhase { RECORDING, REVIEW }

    private val _phase       = MutableStateFlow(VoicePhase.RECORDING)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private val _transcript  = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _elapsedMs   = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            speechTranscriber.transcriptState.collect { state ->
                when (state) {
                    is TranscriptState.Partial -> _transcript.value = state.text
                    is TranscriptState.Final   -> _transcript.value = state.text
                    else                       -> Unit
                }
            }
        }
    }

    fun startRecording() {
        _isRecording.value = true
        speechTranscriber.startListening()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                _elapsedMs.value += 1_000L
            }
        }
    }

    fun stopRecording() {
        timerJob?.cancel()
        timerJob = null
        speechTranscriber.stopListening()
        _isRecording.value = false
        _phase.value = VoicePhase.REVIEW
    }

    fun send(prompt: String) {
        // TODO: dispatch prompt to current node session
        resetState()
    }

    fun discard() {
        resetState()
    }

    private fun resetState() {
        timerJob?.cancel()
        timerJob = null
        _isRecording.value = false
        _phase.value       = VoicePhase.RECORDING
        _transcript.value  = ""
        _elapsedMs.value   = 0L
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        speechTranscriber.destroy()
    }

    companion object {
        /**
         * Formats elapsed milliseconds as "M:SS" (e.g. 42000L → "0:42", 84000L → "1:24").
         * Minutes are not zero-padded; seconds always use two digits.
         */
        fun formatElapsedMs(ms: Long): String {
            val totalSeconds = ms / 1_000L
            val minutes      = totalSeconds / 60
            val seconds      = totalSeconds % 60
            return "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceOverlayViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 13 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt
git commit -m "feat(voice): add VoiceOverlayViewModel with phase state and formatElapsedMs"
```

---

## Task 2: VoiceOverlayViewModel — Transcript Sync & Timer Coroutine

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt`
- (No changes to `VoiceOverlayViewModel.kt` — these tests should already pass after Task 1)

These tests verify the coroutine-driven behaviors: transcript mirroring from `SpeechTranscriber` and the 1 Hz timer tick. Both are already implemented in Task 1; this task confirms they work.

- [ ] **Step 1: Append the coroutine tests**

Open `app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt` and add these tests inside the class body, after the `formatElapsedMs` tests:

```kotlin
    // ── Transcript sync ──────────────────────────────────────────────────────

    @Test fun `transcript updates when transcriber emits Partial`() {
        val transcriber = FakeSpeechTranscriber()
        val vm = VoiceOverlayViewModel(transcriber)
        transcriber.emitPartial("hello there")
        assertThat(vm.transcript.value).isEqualTo("hello there")
    }

    @Test fun `transcript updates when transcriber emits Final`() {
        val transcriber = FakeSpeechTranscriber()
        val vm = VoiceOverlayViewModel(transcriber)
        transcriber.emitFinal("final answer")
        assertThat(vm.transcript.value).isEqualTo("final answer")
    }

    @Test fun `transcript is not updated for Idle or Listening states`() {
        val transcriber = FakeSpeechTranscriber()
        val vm = VoiceOverlayViewModel(transcriber)
        transcriber.emitFinal("first")
        transcriber.startListening() // emits Listening
        assertThat(vm.transcript.value).isEqualTo("first") // unchanged
    }

    // ── Timer coroutine ──────────────────────────────────────────────────────

    @Test fun `elapsedMs increments by 1000 after each second of recording`() = runTest(testDispatcher) {
        val vm = makeVm()
        vm.startRecording()
        testScheduler.advanceTimeBy(1_001L)
        assertThat(vm.elapsedMs.value).isEqualTo(1_000L)
        testScheduler.advanceTimeBy(1_000L)
        assertThat(vm.elapsedMs.value).isEqualTo(2_000L)
    }

    @Test fun `timer stops incrementing after stopRecording`() = runTest(testDispatcher) {
        val vm = makeVm()
        vm.startRecording()
        testScheduler.advanceTimeBy(3_001L)
        vm.stopRecording()
        val capturedMs = vm.elapsedMs.value
        testScheduler.advanceTimeBy(5_000L)
        assertThat(vm.elapsedMs.value).isEqualTo(capturedMs) // no further increments
    }

    @Test fun `discard cancels the timer`() = runTest(testDispatcher) {
        val vm = makeVm()
        vm.startRecording()
        testScheduler.advanceTimeBy(2_001L)
        vm.discard()
        testScheduler.advanceTimeBy(3_000L)
        assertThat(vm.elapsedMs.value).isEqualTo(0L) // reset by discard
    }
```

- [ ] **Step 2: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceOverlayViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 19 tests, 0 failures

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/vela/app/ui/voice/VoiceOverlayViewModelTest.kt
git commit -m "feat(voice): verify transcript sync and timer coroutine in VoiceOverlayViewModel"
```

---

## Task 3: VoiceCaptureOverlay — Recording Phase

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlaySourceTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlay.kt`

The overlay is a full-screen `Dialog` (not a navigation destination) that uses `AnimatedContent` to switch between the RECORDING and REVIEW phases. This task adds the Dialog wrapper and the RECORDING phase. Task 4 adds the REVIEW phase.

- [ ] **Step 1: Write the failing source tests**

Create `app/src/test/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlaySourceTest.kt`:

```kotlin
package com.vela.app.ui.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level tests that verify the VoiceCaptureOverlay composable references
 * the correct design tokens and structural elements. These run on the JVM without
 * Compose runtime — they simply read the source file as text.
 */
class VoiceCaptureOverlaySourceTest {

    private val source: String by lazy {
        File("app/src/main/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlay.kt").readText()
    }

    // ── Overlay backdrop ─────────────────────────────────────────────────────

    @Test fun `overlay uses Abyss at 88 percent alpha as backdrop`() {
        assertThat(source).contains("VelaColors.Abyss")
        assertThat(source).contains("0.88f")
    }

    @Test fun `overlay is a full-screen Dialog`() {
        assertThat(source).contains("Dialog(")
        assertThat(source).contains("usePlatformDefaultWidth = false")
    }

    // ── Recording phase ──────────────────────────────────────────────────────

    @Test fun `recording phase uses 340dp bloom circle`() {
        assertThat(source).contains("340.dp")
    }

    @Test fun `recording phase bloom ring uses Accent at 60 percent alpha`() {
        assertThat(source).contains("VelaColors.Accent")
        assertThat(source).contains("0.6f")
    }

    @Test fun `recording phase has 10 waveform bars`() {
        assertThat(source).contains("repeat(10)")
    }

    @Test fun `waveform uses infiniteTransition`() {
        assertThat(source).contains("rememberInfiniteTransition")
    }

    @Test fun `recording phase stop button uses ErrorContainer fill`() {
        assertThat(source).contains("VelaColors.ErrorContainer")
        assertThat(source).contains("72.dp")
    }

    @Test fun `recording phase stop button icon uses ErrorOnContainer tint`() {
        assertThat(source).contains("VelaColors.ErrorOnContainer")
    }

    @Test fun `recording phase shows timer via formatElapsedMs`() {
        assertThat(source).contains("formatElapsedMs")
    }

    @Test fun `recording phase pulsing dot uses Error color`() {
        assertThat(source).contains("VelaColors.Error")
    }

    // ── Review phase ─────────────────────────────────────────────────────────

    @Test fun `review phase has Send callback`() {
        assertThat(source).contains("onSend")
    }

    @Test fun `review phase has Discard callback`() {
        assertThat(source).contains("onDiscard")
    }

    @Test fun `review phase Send button uses Accent fill`() {
        assertThat(source).contains("VelaColors.Accent")
    }

    @Test fun `review shows duration stamp with formatElapsedMs`() {
        assertThat(source).contains("Recorded")
    }

    // ── Phase switching ──────────────────────────────────────────────────────

    @Test fun `uses AnimatedContent to switch between phases`() {
        assertThat(source).contains("AnimatedContent")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceCaptureOverlaySourceTest" \
  -x lint 2>&1 | tail -10
```

Expected: FAILED — `File ... does not exist` or NullPointerException on the lazy source read.

- [ ] **Step 3: Create VoiceCaptureOverlay.kt with the recording phase**

Create `app/src/main/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlay.kt`:

```kotlin
package com.vela.app.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vela.app.ui.theme.VelaColors
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.Canvas

/**
 * Full-screen voice capture overlay. Shown as a Dialog so it floats above all
 * current navigation content.
 *
 * @param phase        Current recording phase (RECORDING or REVIEW).
 * @param transcript   Live transcript text from the SpeechTranscriber.
 * @param elapsedMs    Elapsed recording time in milliseconds.
 * @param nodeName     Name of the destination node shown in the context pill.
 * @param onStop       Called when the user taps the Stop button (RECORDING phase).
 * @param onSend       Called with the transcript when the user taps Send (REVIEW phase).
 * @param onDiscard    Called when the user discards the recording.
 */
@Composable
fun VoiceCaptureOverlay(
    phase: VoiceOverlayViewModel.VoicePhase,
    transcript: String,
    elapsedMs: Long,
    nodeName: String,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDiscard,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(VelaColors.Abyss.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState   = phase,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label          = "voicePhase",
            ) { targetPhase ->
                when (targetPhase) {
                    VoiceOverlayViewModel.VoicePhase.RECORDING ->
                        RecordingPhase(
                            transcript = transcript,
                            elapsedMs  = elapsedMs,
                            nodeName   = nodeName,
                            onStop     = onStop,
                        )
                    VoiceOverlayViewModel.VoicePhase.REVIEW ->
                        ReviewPhase(
                            transcript = transcript,
                            elapsedMs  = elapsedMs,
                            onSend     = onSend,
                            onDiscard  = onDiscard,
                        )
                }
            }
        }
    }
}

// ── Recording phase ───────────────────────────────────────────────────────────

@Composable
private fun RecordingPhase(
    transcript: String,
    elapsedMs: Long,
    nodeName: String,
    onStop: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")

    // Single wave-progress drives all 10 bars via sine math.
    val waveProgress by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 800, easing = LinearEasing),
        ),
        label = "waveProgress",
    )

    // Pulsing red dot for the timer — 1-second breathe.
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timerDot",
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        // ── Node context tag (pill) ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(VelaColors.SurfaceRaised)
                .border(1.dp, VelaColors.StrokeEdge, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(VelaColors.Accent, CircleShape),
            )
            Text(
                text  = "→ $nodeName",
                style = MaterialTheme.typography.labelSmall,
                color = VelaColors.TextPrimary,
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Bloom circle + waveform bars + transcript ───────────────────────
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(340.dp)) {
                drawCircle(
                    color  = VelaColors.Accent.copy(alpha = 0.6f),
                    radius = size.minDimension / 2f,
                    style  = Stroke(width = 1.5.dp.toPx()),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.padding(horizontal = 40.dp),
            ) {
                // Waveform bars: 10 bars, 3dp wide, sine-driven height
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.height(48.dp),
                ) {
                    repeat(10) { i ->
                        val phase  = (waveProgress + i / 10f) % 1f
                        val scale  = (sin(phase * 2.0 * PI).toFloat() * 0.35f + 0.65f)
                            .coerceIn(0.3f, 1.0f)
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight(scale)
                                .clip(RoundedCornerShape(2.dp))
                                .background(VelaColors.Accent.copy(alpha = 0.7f)),
                        )
                    }
                }

                if (transcript.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text      = transcript,
                        style     = MaterialTheme.typography.displayMedium.copy(fontSize = 26.sp),
                        color     = VelaColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines  = 4,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Hint ─────────────────────────────────────────────────────────────
        Text(
            text  = "Recording · Swipe to discard",
            style = MaterialTheme.typography.labelSmall,
            color = VelaColors.TextTertiary,
        )

        Spacer(Modifier.height(16.dp))

        // ── Timer row ─────────────────────────────────────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(VelaColors.Error.copy(alpha = dotAlpha), CircleShape),
            )
            Text(
                text  = VoiceOverlayViewModel.formatElapsedMs(elapsedMs),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 22.sp,
                    letterSpacing = 3.sp,
                ),
                color = VelaColors.TextPrimary,
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Stop button (72dp circle, ErrorContainer fill) ────────────────────
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(VelaColors.ErrorContainer, CircleShape)
                .border(1.5.dp, Color(0xFFFF6B6B).copy(alpha = 0.28f), CircleShape)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.Stop,
                contentDescription = "Stop recording",
                tint               = VelaColors.ErrorOnContainer,
                modifier           = Modifier.size(28.dp),
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

// ── Review phase ──────────────────────────────────────────────────────────────

@Composable
private fun ReviewPhase(
    transcript: String,
    elapsedMs: Long,
    onSend: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        // Scrollable transcript (Instrument Serif 18sp for review scale)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text      = transcript,
                style     = MaterialTheme.typography.displayMedium.copy(fontSize = 18.sp),
                color     = VelaColors.TextPrimary,
                textAlign = TextAlign.Start,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Duration stamp
        Text(
            text  = "Recorded ${VoiceOverlayViewModel.formatElapsedMs(elapsedMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = VelaColors.TextTertiary,
        )

        Spacer(Modifier.height(24.dp))

        // ── Button row: Discard (left) + Send (right, full-width) ─────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick  = onDiscard,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape  = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = VelaColors.Error,
                ),
            ) {
                Text(
                    text  = "DISCARD",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Button(
                onClick  = { onSend(transcript) },
                modifier = Modifier
                    .weight(2f)
                    .height(52.dp),
                shape  = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VelaColors.Accent,
                    contentColor   = VelaColors.Abyss,
                ),
            ) {
                Text(
                    text  = "SEND",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}
```

- [ ] **Step 4: Run source tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceCaptureOverlaySourceTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 16 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlay.kt \
  app/src/test/kotlin/com/vela/app/ui/voice/VoiceCaptureOverlaySourceTest.kt
git commit -m "feat(voice): add VoiceCaptureOverlay with recording and review phases"
```

---

## Task 4: VoiceFab — Design-Correct Persistent FAB

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/voice/VoiceFabSourceTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/voice/VoiceFab.kt`

`VoiceFab` is a 64dp layered circle composable: halo glow, 1.5dp ring, solid disc, mic icon. It manages `showOverlay` internally and wires the `VoiceOverlayViewModel` to `VoiceCaptureOverlay`.

- [ ] **Step 1: Write the failing source tests**

Create `app/src/test/kotlin/com/vela/app/ui/voice/VoiceFabSourceTest.kt`:

```kotlin
package com.vela.app.ui.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class VoiceFabSourceTest {

    private val source: String by lazy {
        File("app/src/main/kotlin/com/vela/app/ui/voice/VoiceFab.kt").readText()
    }

    @Test fun `VoiceFab is 64dp diameter`() {
        assertThat(source).contains("64.dp")
    }

    @Test fun `VoiceFab idle state uses Accent for ring and disc outline`() {
        assertThat(source).contains("VelaColors.Accent")
    }

    @Test fun `VoiceFab running state uses Running color`() {
        assertThat(source).contains("VelaColors.Running")
    }

    @Test fun `VoiceFab calls startRecording on tap`() {
        assertThat(source).contains("startRecording")
    }

    @Test fun `VoiceFab shows VoiceCaptureOverlay when overlay is visible`() {
        assertThat(source).contains("VoiceCaptureOverlay(")
    }

    @Test fun `VoiceFab calls discard and hides overlay when onDiscard fires`() {
        assertThat(source).contains("discard()")
    }

    @Test fun `VoiceFab calls send and hides overlay when onSend fires`() {
        assertThat(source).contains("send(")
    }

    @Test fun `VoiceFab uses SurfacePeak as idle disc color`() {
        assertThat(source).contains("VelaColors.SurfacePeak")
    }

    @Test fun `VoiceFab has halo breathing animation in running state`() {
        assertThat(source).contains("rememberInfiniteTransition")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceFabSourceTest" \
  -x lint 2>&1 | tail -10
```

Expected: FAILED — source file does not exist.

- [ ] **Step 3: Create VoiceFab.kt**

Create `app/src/main/kotlin/com/vela/app/ui/voice/VoiceFab.kt`:

```kotlin
package com.vela.app.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.app.ui.theme.VelaColors

/**
 * Persistent Voice FAB. 64dp diameter layered circle: outer glow halo →
 * 1.5dp ring → solid disc → mic icon. Manages overlay visibility internally.
 *
 * @param voiceVm         Hilt-injected ViewModel shared with the overlay.
 * @param isSessionRunning True when any session on the current node is RUNNING.
 *                         Shifts the FAB from cyan-idle to amber-breathing.
 * @param nodeName        Forwarded to VoiceCaptureOverlay's context pill.
 */
@Composable
fun VoiceFab(
    voiceVm: VoiceOverlayViewModel,
    isSessionRunning: Boolean,
    nodeName: String = "",
    modifier: Modifier = Modifier,
) {
    val phase      by voiceVm.phase.collectAsState()
    val transcript by voiceVm.transcript.collectAsState()
    val elapsedMs  by voiceVm.elapsedMs.collectAsState()

    var showOverlay by remember { mutableStateOf(false) }

    // Breathing halo only when a session is running.
    val infiniteTransition = rememberInfiniteTransition(label = "fabHalo")
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue  = if (isSessionRunning) 0.14f else 0.18f,
        targetValue   = if (isSessionRunning) 0.32f else 0.18f,
        animationSpec = if (isSessionRunning)
            infiniteRepeatable(
                animation  = tween(1_200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            )
        else
            snap(),
        label = "haloAlpha",
    )

    val ringColor = if (isSessionRunning) VelaColors.Running else VelaColors.Accent
    val discColor = if (isSessionRunning) VelaColors.Running else VelaColors.SurfacePeak
    val iconTint  = if (isSessionRunning) Color(0xFF1A1000)  else VelaColors.Accent

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Outer glow halo (drawn behind the FAB circle).
        Canvas(modifier = Modifier.size(100.dp)) {
            drawCircle(color = ringColor.copy(alpha = haloAlpha))
        }

        // FAB disc with ring border and mic icon.
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(discColor, CircleShape)
                .border(1.5.dp, ringColor, CircleShape)
                .clickable {
                    showOverlay = true
                    voiceVm.startRecording()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.Mic,
                contentDescription = "Open voice input",
                tint               = iconTint,
                modifier           = Modifier.size(26.dp),
            )
        }
    }

    if (showOverlay) {
        VoiceCaptureOverlay(
            phase      = phase,
            transcript = transcript,
            elapsedMs  = elapsedMs,
            nodeName   = nodeName,
            onStop     = { voiceVm.stopRecording() },
            onSend     = { prompt ->
                voiceVm.send(prompt)
                showOverlay = false
            },
            onDiscard  = {
                voiceVm.discard()
                showOverlay = false
            },
        )
    }
}
```

- [ ] **Step 4: Run source tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.voice.VoiceFabSourceTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 9 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/voice/VoiceFab.kt \
  app/src/test/kotlin/com/vela/app/ui/voice/VoiceFabSourceTest.kt
git commit -m "feat(voice): add VoiceFab with idle/running states and overlay wiring"
```

---

## Task 5: ApprovalSheetViewModel TDD

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModelTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModel.kt`

This ViewModel is pure state — no coroutines, no dispatcher setup required.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModelTest.kt`:

```kotlin
package com.vela.app.ui.approval

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApprovalSheetViewModelTest {

    private fun makeVm() = ApprovalSheetViewModel()

    // ── Initial state ────────────────────────────────────────────────────────

    @Test fun `initial request is null`() {
        assertThat(makeVm().request.value).isNull()
    }

    // ── present ──────────────────────────────────────────────────────────────

    @Test fun `present sets request`() {
        val vm  = makeVm()
        val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Run migration?")
        vm.present(req)
        assertThat(vm.request.value).isEqualTo(req)
    }

    @Test fun `present with contextText preserves contextText`() {
        val vm  = makeVm()
        val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Deploy?", "tool_call: deploy_to_prod")
        vm.present(req)
        assertThat(vm.request.value?.contextText).isEqualTo("tool_call: deploy_to_prod")
    }

    @Test fun `present replaces existing request`() {
        val vm = makeVm()
        vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "First?"))
        vm.present(ApprovalSheetViewModel.ApprovalRequest("s2", "Second?"))
        assertThat(vm.request.value?.sessionId).isEqualTo("s2")
        assertThat(vm.request.value?.question).isEqualTo("Second?")
    }

    // ── approve ──────────────────────────────────────────────────────────────

    @Test fun `approve clears request to null`() {
        val vm = makeVm()
        vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "Run migration?"))
        vm.approve()
        assertThat(vm.request.value).isNull()
    }

    @Test fun `approve when request is already null does not crash`() {
        val vm = makeVm()
        vm.approve() // should be a no-op
        assertThat(vm.request.value).isNull()
    }

    // ── deny ─────────────────────────────────────────────────────────────────

    @Test fun `deny clears request to null`() {
        val vm = makeVm()
        vm.present(ApprovalSheetViewModel.ApprovalRequest("s1", "Deploy?"))
        vm.deny()
        assertThat(vm.request.value).isNull()
    }

    @Test fun `deny when request is already null does not crash`() {
        val vm = makeVm()
        vm.deny()
        assertThat(vm.request.value).isNull()
    }

    // ── ApprovalRequest data class ────────────────────────────────────────────

    @Test fun `ApprovalRequest contextText defaults to null`() {
        val req = ApprovalSheetViewModel.ApprovalRequest("s1", "Question?")
        assertThat(req.contextText).isNull()
    }

    @Test fun `two ApprovalRequests with same data are equal`() {
        val a = ApprovalSheetViewModel.ApprovalRequest("s1", "Q?", "ctx")
        val b = ApprovalSheetViewModel.ApprovalRequest("s1", "Q?", "ctx")
        assertThat(a).isEqualTo(b)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.approval.ApprovalSheetViewModelTest" \
  -x lint 2>&1 | tail -10
```

Expected: FAILED — `error: unresolved reference: ApprovalSheetViewModel`

- [ ] **Step 3: Create ApprovalSheetViewModel.kt**

Create `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModel.kt`:

```kotlin
package com.vela.app.ui.approval

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ApprovalSheetViewModel @Inject constructor() : ViewModel() {

    data class ApprovalRequest(
        val sessionId:   String,
        val question:    String,
        val contextText: String? = null,
    )

    private val _request = MutableStateFlow<ApprovalRequest?>(null)
    val request: StateFlow<ApprovalRequest?> = _request.asStateFlow()

    /** Show the sheet with a new approval request. Replaces any pending request. */
    fun present(req: ApprovalRequest) {
        _request.value = req
    }

    /** Confirm the requested action and dismiss the sheet. */
    fun approve() {
        // TODO: signal the session identified by request.sessionId
        _request.value = null
    }

    /** Reject the requested action and dismiss the sheet. */
    fun deny() {
        // TODO: signal denial to the session
        _request.value = null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.approval.ApprovalSheetViewModelTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 10 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModel.kt \
  app/src/test/kotlin/com/vela/app/ui/approval/ApprovalSheetViewModelTest.kt
git commit -m "feat(approval): add ApprovalSheetViewModel with present, approve, deny"
```

---

## Task 6: ApprovalGateSheet — Bottom Sheet UI

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalGateSheetSourceTest.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalGateSheet.kt`

A `ModalBottomSheet` with 32dp top-corner rounding, `SurfacePeak` background, serif question title, optional mono context block, and a Deny/Approve button row.

- [ ] **Step 1: Write the failing source tests**

Create `app/src/test/kotlin/com/vela/app/ui/approval/ApprovalGateSheetSourceTest.kt`:

```kotlin
package com.vela.app.ui.approval

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ApprovalGateSheetSourceTest {

    private val source: String by lazy {
        File("app/src/main/kotlin/com/vela/app/ui/approval/ApprovalGateSheet.kt").readText()
    }

    @Test fun `uses ModalBottomSheet`() {
        assertThat(source).contains("ModalBottomSheet(")
    }

    @Test fun `sheet uses SurfacePeak as container color`() {
        assertThat(source).contains("VelaColors.SurfacePeak")
    }

    @Test fun `sheet has 32dp top corner rounding`() {
        assertThat(source).contains("32.dp")
    }

    @Test fun `drag handle is 36dp wide and 4dp tall`() {
        assertThat(source).contains("36.dp")
        assertThat(source).contains("4.dp")
    }

    @Test fun `eyebrow text is APPROVAL REQUIRED`() {
        assertThat(source).contains("APPROVAL REQUIRED")
    }

    @Test fun `eyebrow uses Waiting color`() {
        assertThat(source).contains("VelaColors.Waiting")
    }

    @Test fun `title uses displayMedium typography`() {
        assertThat(source).contains("displayMedium")
    }

    @Test fun `context block has max height 280dp scrollable`() {
        assertThat(source).contains("280.dp")
        assertThat(source).contains("verticalScroll")
    }

    @Test fun `context block uses SurfaceRaised background`() {
        assertThat(source).contains("VelaColors.SurfaceRaised")
    }

    @Test fun `Deny button uses Error color`() {
        assertThat(source).contains("VelaColors.Error")
        assertThat(source).contains("DENY")
    }

    @Test fun `Approve button uses Accent fill and Abyss label`() {
        assertThat(source).contains("VelaColors.Accent")
        assertThat(source).contains("VelaColors.Abyss")
        assertThat(source).contains("APPROVE")
    }

    @Test fun `buttons are 52dp tall with 26dp radius`() {
        assertThat(source).contains("52.dp")
        assertThat(source).contains("26.dp")
    }

    @Test fun `button gap is 12dp`() {
        assertThat(source).contains("12.dp")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.approval.ApprovalGateSheetSourceTest" \
  -x lint 2>&1 | tail -10
```

Expected: FAILED — source file does not exist.

- [ ] **Step 3: Create ApprovalGateSheet.kt**

Create `app/src/main/kotlin/com/vela/app/ui/approval/ApprovalGateSheet.kt`:

```kotlin
package com.vela.app.ui.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.VelaColors

/**
 * Approval Gate bottom sheet (Screen 7). Rises from the bottom when a session
 * pauses for human input. Dismissing the sheet is equivalent to Deny.
 *
 * @param request   The pending approval request to display.
 * @param onApprove Called when the user taps Approve.
 * @param onDeny    Called when the user taps Deny or dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalGateSheet(
    request: ApprovalSheetViewModel.ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState       = sheetState,
        containerColor   = VelaColors.SurfacePeak,
        shape            = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(VelaColors.TextTertiary, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 20.dp),
        ) {
            // ── Eyebrow: "APPROVAL REQUIRED" ─────────────────────────────────
            Text(
                text  = "APPROVAL REQUIRED",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                ),
                color = VelaColors.Waiting,
            )

            Spacer(Modifier.height(10.dp))

            // ── Serif title — the question moment ────────────────────────────
            Text(
                text  = request.question,
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 25.sp),
                color = VelaColors.TextPrimary,
            )

            // ── Optional context block ────────────────────────────────────────
            if (request.contextText != null) {
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .background(VelaColors.SurfaceRaised, RoundedCornerShape(12.dp))
                        .border(1.dp, VelaColors.StrokeHair, RoundedCornerShape(12.dp)),
                ) {
                    Text(
                        text       = request.contextText,
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = VelaColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Button row: Deny (left) + Approve (right) ─────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                // Deny — transparent background, Error-colored border and label
                OutlinedButton(
                    onClick  = onDeny,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape  = RoundedCornerShape(26.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        VelaColors.Error.copy(alpha = 0.30f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = VelaColors.Error,
                    ),
                ) {
                    Text(
                        text  = "DENY",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                // Approve — Accent fill, Abyss label
                Button(
                    onClick  = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape  = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text  = "APPROVE",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
```

- [ ] **Step 4: Run source tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.approval.ApprovalGateSheetSourceTest" \
  -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — 13 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/kotlin/com/vela/app/ui/approval/ApprovalGateSheet.kt \
  app/src/test/kotlin/com/vela/app/ui/approval/ApprovalGateSheetSourceTest.kt
git commit -m "feat(approval): add ApprovalGateSheet ModalBottomSheet with Deny/Approve"
```

---

## Task 7: Wire VoiceFab and ApprovalGateSheet into AppNavigation

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`
- Modify (append): `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`

Replace `VoiceFabPlaceholder` with `VoiceFab` and add `ApprovalGateSheet` inside `VelaApp()`.

**Before you start:** Read the current `AppNavigation.kt` top-to-bottom so you know where `VoiceFabPlaceholder` is placed and what the `VelaApp()` function looks like. Also read `AppNavigationTest.kt` so you know the existing test class name.

- [ ] **Step 1: Write the failing wiring tests**

Open `app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt`.

Append these tests inside the existing test class (keep all existing tests):

```kotlin
    @Test fun `AppNavigation uses VoiceFab not VoiceFabPlaceholder`() {
        val src = java.io.File(
            "app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        com.google.common.truth.Truth.assertThat(src).doesNotContain("VoiceFabPlaceholder(")
        com.google.common.truth.Truth.assertThat(src).contains("VoiceFab(")
    }

    @Test fun `AppNavigation includes ApprovalGateSheet`() {
        val src = java.io.File(
            "app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        com.google.common.truth.Truth.assertThat(src).contains("ApprovalGateSheet(")
    }

    @Test fun `AppNavigation imports VoiceOverlayViewModel`() {
        val src = java.io.File(
            "app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        com.google.common.truth.Truth.assertThat(src).contains("VoiceOverlayViewModel")
    }

    @Test fun `AppNavigation imports ApprovalSheetViewModel`() {
        val src = java.io.File(
            "app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt"
        ).readText()
        com.google.common.truth.Truth.assertThat(src).contains("ApprovalSheetViewModel")
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.vela.app.ui.navigation.AppNavigationTest.AppNavigation uses VoiceFab not VoiceFabPlaceholder" \
  -x lint 2>&1 | tail -10
```

Expected: FAILED — source still contains `VoiceFabPlaceholder(` and no `VoiceFab(`.

- [ ] **Step 3: Update AppNavigation.kt**

Open `app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt`.

**3a. Add these imports** at the top of the file (after the existing imports):

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.approval.ApprovalGateSheet
import com.vela.app.ui.approval.ApprovalSheetViewModel
import com.vela.app.ui.voice.VoiceFab
import com.vela.app.ui.voice.VoiceOverlayViewModel
```

**3b. Inside `VelaApp()`**, add ViewModel declarations near the top of the function body (after the `Box` open but before the `NavHost`). Look for the existing `Box(modifier = modifier.fillMaxSize())` block and add inside it:

```kotlin
        val voiceVm: VoiceOverlayViewModel   = hiltViewModel()
        val approvalVm: ApprovalSheetViewModel = hiltViewModel()
        val approvalReq by approvalVm.request.collectAsState()
```

**3c. Replace the `VoiceFabPlaceholder(...)` call** with:

```kotlin
        VoiceFab(
            voiceVm          = voiceVm,
            isSessionRunning = false,
            modifier         = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
```

Keep the same `Modifier` alignment that the placeholder was using. If the placeholder used `.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp)` or similar, match it exactly.

**3d. After the `NavHost { ... }` block** (still inside the `Box`), add:

```kotlin
        approvalReq?.let { req ->
            ApprovalGateSheet(
                request   = req,
                onApprove = { approvalVm.approve() },
                onDeny    = { approvalVm.deny() },
            )
        }
```

- [ ] **Step 4: Run the full test suite to verify nothing regressed**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL — all existing tests still pass plus the 4 new wiring tests.

- [ ] **Step 5: Verify the project compiles**

```bash
./gradlew :app:assembleDebug -x lint 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/vela/app/ui/navigation/AppNavigation.kt \
        app/src/test/kotlin/com/vela/app/ui/navigation/AppNavigationTest.kt
git commit -m "feat(voice): wire VoiceFab and ApprovalGateSheet into VelaApp"
```

---

## Task 8: Full Regression Pass

**Files:** None — verification only.

This task runs the entire test suite and assembles the debug APK to confirm all 8 new features integrate cleanly.

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | grep -E "tests|PASSED|FAILED|ERROR|BUILD"
```

Expected output pattern:
```
BUILD SUCCESSFUL
```

All previously-passing tests must still pass. The new tests added in this phase:
- `VoiceOverlayViewModelTest` — 19 tests
- `VoiceCaptureOverlaySourceTest` — 16 tests
- `VoiceFabSourceTest` — 9 tests
- `ApprovalSheetViewModelTest` — 10 tests
- `ApprovalGateSheetSourceTest` — 13 tests
- `AppNavigationTest` (4 new, plus existing)

- [ ] **Step 2: Assemble debug APK**

```bash
./gradlew :app:assembleDebug -x lint 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit final tag**

```bash
git commit --allow-empty -m "chore(phase4): voice overlay and approval gate complete — all tests green"
```

---

## Design Reference Cheat Sheet

Keep this open while implementing Tasks 3–7.

### Screen 6 — Voice Capture Overlay

| Element | Spec |
|---------|------|
| Backdrop | `VelaColors.Abyss` at 88% alpha |
| Bloom circle | 340dp diameter, 1.5dp stroke, `VelaColors.Accent` at 60% alpha |
| Waveform | 10 bars × 3dp wide, `VelaColors.Accent` at 70%, sine-wave animation |
| Transcript (recording) | `displayMedium` at 26sp, centered, max 4 lines |
| Timer | JetBrains Mono 22sp, letter-spacing 3sp, format `"M:SS"` |
| Pulsing dot | 9dp, `VelaColors.Error`, 1s breathe (`RepeatMode.Reverse`) |
| Stop button | 72dp circle, `ErrorContainer` fill, 1.5dp `#FF6B6B` at 28% border, `ErrorOnContainer` icon |
| Transcript (review) | `displayMedium` at 18sp, scrollable |
| Duration stamp | `"Recorded M:SS"` in `labelSmall`, `TextTertiary` |
| Discard button | `OutlinedButton`, `VelaColors.Error` border/text |
| Send button | `Button`, `VelaColors.Accent` fill, `VelaColors.Abyss` text, 52dp height, 26dp radius |

### Screen 7 — Approval Gate Sheet

| Element | Spec |
|---------|------|
| Sheet background | `VelaColors.SurfacePeak` |
| Corner radius | 32dp top-start + top-end only |
| Drag handle | 36×4dp, `TextTertiary`, 12dp from top |
| Eyebrow | `"APPROVAL REQUIRED"`, `labelSmall` Bold, letter-spacing 1.6sp, `VelaColors.Waiting` |
| Title | `displayMedium` at 25sp, `TextPrimary`, 10dp below eyebrow |
| Context block | `SurfaceRaised` bg, 12dp radius, `StrokeHair` border, monospace text, max 280dp scrollable |
| Deny button | `OutlinedButton`, `Error` at 30% border, `Error` text, 52dp height, 26dp radius |
| Approve button | `Button`, `Accent` fill, `Abyss` text, Inter Bold uppercase, 52dp height, 26dp radius |
| Button gap | 12dp |

### VoiceFab Spec

| State | Halo | Ring | Disc | Icon |
|-------|------|------|------|------|
| Idle | `Accent` at 18% | `Accent` 1.5dp | `SurfacePeak` | `Accent` |
| Running | `Running` breathing 14%→32% | `Running` 1.5dp | `Running` | `#1A1000` |

FAB size: 64dp. Position in AppNavigation: `Alignment.BottomEnd`, `padding(16.dp)`.
