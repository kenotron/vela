# Phase 1 Cleanup Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Address 4 code quality suggestions from Phase 1 review before starting Phase 2.
**Architecture:** Minor, surgical fixes to existing files. No new modules or architectural changes.
**Tech Stack:** Kotlin, JUnit, Google Truth, Compose

---

## Context

Phase 1 code review (verdict: APPROVED) flagged 4 "nice-to-have" suggestions. The 2 important
issues (.gitignore and Room schemas) were already resolved in commits `f6e114d` and `f2d01b2`.
These remaining items are small improvements that reduce tech debt before Phase 2.

---

### Task 1: Replace redundant interface-check test with meaningful behavior test

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/ai/FakeGemmaEngineTest.kt:33-37`

**Why:** `engineImplementsGemmaEngineInterface` asserts `isInstanceOf(GemmaEngine::class.java)`.
If `FakeGemmaEngine` didn't implement `GemmaEngine`, the file wouldn't compile — the test would
never run. Replace it with a test that exercises actual edge-case behavior.

**Step 1: Replace the test**

In `app/src/test/kotlin/com/vela/app/ai/FakeGemmaEngineTest.kt`, replace lines 33-37:

```kotlin
// REMOVE:
    @Test
    fun engineImplementsGemmaEngineInterface() {
        val engine = FakeGemmaEngine()
        assertThat(engine).isInstanceOf(GemmaEngine::class.java)
    }
```

With:

```kotlin
// ADD:
    @Test
    fun processTextHandlesUnicodeAndSpecialCharacters() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val response = engine.processText("Héllo wörld! 你好 🌍")
        assertThat(response).isNotEmpty()
    }
```

**Step 2: Run tests to verify**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.FakeGemmaEngineTest" --info`
Expected: 4 tests pass (the 3 existing + 1 new replacement)

**Step 3: Commit**

```
git add app/src/test/kotlin/com/vela/app/ai/FakeGemmaEngineTest.kt
git commit -m "test: replace compile-time interface check with unicode edge-case test"
```

---

### Task 2: Extract sample rate constant to eliminate duplication

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt:16-21`
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt:47-62`

**Why:** The sample rate `16000` appears in `VoiceCapture` (as a default parameter) and twice in
`ConversationScreen.kt` (lines 52 and 58). If the rate changes, `ConversationScreen`'s
`AudioRecord` will silently mismatch `VoiceCapture`, producing corrupted WAV files.

**Step 1: Add companion object constant to VoiceCapture**

In `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt`, add a companion object inside the
class body and update the default parameter to reference it.

Replace:

```kotlin
class VoiceCapture(
    private val outputDir: File,
    private val audioRecordFactory: () -> AudioRecordWrapper,
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitsPerSample: Int = 16,
) {
```

With:

```kotlin
class VoiceCapture(
    private val outputDir: File,
    private val audioRecordFactory: () -> AudioRecordWrapper,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelCount: Int = 1,
    private val bitsPerSample: Int = 16,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
    }
```

**Step 2: Update ConversationScreen to use the constant**

In `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`, replace both
hardcoded `16000` values (lines 52 and 58) with `VoiceCapture.DEFAULT_SAMPLE_RATE`.

Replace the `audioRecordFactory` lambda (lines 50-62):

```kotlin
            audioRecordFactory = {
                val minBufSize = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize,
                )
```

With:

```kotlin
            audioRecordFactory = {
                val sampleRate = VoiceCapture.DEFAULT_SAMPLE_RATE
                val minBufSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize,
                )
```

**Step 3: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest --info`
Expected: 12 tests pass (no regressions)

**Step 4: Commit**

```
git add app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt \
       app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt
git commit -m "refactor: extract VoiceCapture.DEFAULT_SAMPLE_RATE constant to eliminate duplication"
```

---

### Task 3: Remove redundant Thread.sleep(10) from recording loop

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt:50-56`

**Why:** `AudioRecord.read()` already blocks until the requested byte count is available (~100 ms
of audio at 16 kHz mono 16-bit). The extra `Thread.sleep(10)` adds 10 ms latency per iteration
for no benefit.

**Step 1: Remove the sleep**

In `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt`, remove line 55:

Replace:

```kotlin
                while (_isRecording.value) {
                    val bytesRead = rec.read(buffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    }
                    Thread.sleep(10)
                }
```

With:

```kotlin
                while (_isRecording.value) {
                    val bytesRead = rec.read(buffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
```

**Step 2: Run VoiceCapture tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest" --info`
Expected: 4 tests pass

**Step 3: Commit**

```
git add app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt
git commit -m "fix: remove redundant Thread.sleep(10) from recording loop"
```

---

### Task 4: Remove timing dependency in VoiceCaptureTest

**Files:**
- Modify: `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt:39-41`

**Why:** `Thread.sleep(50)` is an implicit timing assumption. `stopCapture()` calls
`recordingThread?.join(2000)` internally, which waits for the thread to finish cleanly. The WAV
header write happens inside the thread before it exits, so the file is complete after `stopCapture()`
returns — no sleep needed.

**Step 1: Remove the sleep**

In `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt`, remove line 40:

Replace:

```kotlin
        voiceCapture.startCapture()
        Thread.sleep(50) // Allow recording thread to write at least one audio buffer
        val filePath = voiceCapture.stopCapture()
```

With:

```kotlin
        voiceCapture.startCapture()
        val filePath = voiceCapture.stopCapture()
```

**Step 2: Run VoiceCapture tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest" --info`
Expected: 4 tests pass (the `join(2000)` in `stopCapture` ensures the thread completes)

**Step 3: Run all unit tests for final verification**

Run: `./gradlew :app:testDebugUnitTest --info`
Expected: 12 tests pass

**Step 4: Commit**

```
git add app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt
git commit -m "test: remove timing dependency Thread.sleep(50) from VoiceCaptureTest"
```