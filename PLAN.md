# Vela: Harness Refactor — Implementation Plan

**Goal**: Phase 1 = clean Kotlin harness (InferenceProvider abstraction, AgentOrchestrator extracted from ViewModel). ~~Phase 2 = llama.cpp via NDK as primary provider with Gemma 3 4B IT GGUF~~ **Phase 2 superseded** — see note below.

> **Architecture update (2026-04):** Vela no longer targets a local on-device inference model. The intelligence layer is Claude via Amplifier nodes on the network. Phase 1's `InferenceProvider` abstraction remains valid architecture — the primary implementation becomes `AmplifierNodeProvider` (delegates to an Amplifier node running Claude) rather than a local GGUF model. `MlKitInferenceProvider` is retained as a legacy fallback. Phase 2 (llama.cpp NDK integration, Gemma GGUF download) is cancelled.

**Package**: `com.vela.app`
**Source root**: `app/src/main/kotlin/com/vela/app`
**Min SDK**: 26 | **Compile SDK**: 35 | **JVM target**: 17

---

## PHASE 1: Clean Kotlin Harness

### P1-T1 — Create `InferenceProvider.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/InferenceProvider.kt`

```kotlin
package com.vela.app.ai

import kotlinx.coroutines.flow.Flow

/**
 * Unified inference abstraction. Any model backend (ML Kit, llama.cpp, HTTP) implements this.
 * The AgentOrchestrator talks only to InferenceProvider — never to backend-specific classes.
 */
interface InferenceProvider {
    val name: String

    /** True if provider can handle inference right now (model loaded, AICore available, etc.). */
    suspend fun isAvailable(): Boolean

    /**
     * Stream token chunks for [prompt]. Emits partial text deltas.
     * Caller accumulates into full response.
     */
    fun streamText(prompt: String): Flow<String>

    /** Optional: release resources (close model, free native memory). */
    fun shutdown() {}
}
```

---

### P1-T2 — Create `AgentOrchestrator.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/AgentOrchestrator.kt`

Extract the `agentLoop` logic from `ConversationViewModel`. This class has ZERO Android dependencies (no Context, no ViewModel, no StateFlow). Pure Kotlin.

```kotlin
package com.vela.app.ai

import com.vela.app.ai.tools.ToolRegistry
import com.vela.app.ai.tools.ToolCallParser
import kotlinx.coroutines.flow.Flow

/**
 * Drives the agentic loop: build prompt → stream LLM → parse tool call → execute → repeat.
 * Pure Kotlin — no Android deps. Injected into ConversationViewModel via Hilt.
 *
 * Callbacks let the ViewModel update UI StateFlows without this class knowing about them.
 */
class AgentOrchestrator(
    private val providerRegistry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
) {
    companion object { const val MAX_STEPS = 4 }

    /**
     * Run the full agentic loop for [userInput].
     * @param onTokenChunk  called with each streaming token delta
     * @param onToolStart   called with tool name when a tool begins executing
     * @param onToolEnd     called when tool execution finishes
     * @param onStepChange  called with (currentStep, maxSteps) at the start of each loop step
     */
    suspend fun runLoop(
        userInput: String,
        onTokenChunk: suspend (String) -> Unit = {},
        onToolStart: suspend (String) -> Unit = {},
        onToolEnd: suspend () -> Unit = {},
        onStepChange: suspend (current: Int, max: Int) -> Unit = { _, _ -> },
    ): String {
        val provider = providerRegistry.current()
        var prompt = PromptBuilder.buildWithTools(userInput, toolRegistry.all())
        var lastResponse = ""

        for (step in 1..MAX_STEPS) {
            onStepChange(step, MAX_STEPS)
            lastResponse = streamAndCollect(provider, prompt, onTokenChunk)

            val toolCall = ToolCallParser.parse(lastResponse)
            if (toolCall == null || !toolRegistry.contains(toolCall.toolName)) {
                return lastResponse
            }

            onToolStart(toolCall.toolName)
            val toolResult = try {
                toolRegistry.execute(toolCall.toolName, toolCall.args)
            } catch (e: Exception) {
                "Error running ${toolCall.toolName}: ${e.message?.take(120)}"
            }
            onToolEnd()

            prompt = appendExchange(prompt, lastResponse, toolResult)
        }

        return streamAndCollect(provider, prompt, onTokenChunk)
    }

    private suspend fun streamAndCollect(
        provider: InferenceProvider,
        prompt: String,
        onChunk: suspend (String) -> Unit,
    ): String {
        val sb = StringBuilder()
        provider.streamText(prompt).collect { chunk ->
            sb.append(chunk)
            onChunk(chunk)
        }
        return sb.toString().trim()
    }

    private fun appendExchange(prompt: String, toolCallJson: String, toolResult: String): String {
        val base = prompt.trimEnd().removeSuffix("Vela:").trimEnd()
        return "$base\nVela: $toolCallJson\nTool result: $toolResult\nVela:"
    }
}
```

Also create **`PromptBuilder.kt`** — provider-agnostic prompt assembly:
**New file**: `app/src/main/kotlin/com/vela/app/ai/PromptBuilder.kt`

```kotlin
package com.vela.app.ai

import com.vela.app.ai.tools.Tool

/**
 * Builds prompts for the agentic loop. Provider-agnostic — uses the plain-text
 * JSON-in-prompt format that works for both ML Kit and Gemma GGUF models.
 *
 * For AmplifierNodeProvider this output is forwarded to the Amplifier node,
 * which applies its own prompt formatting before calling Claude.
 */
object PromptBuilder {
    private const val MAX_USER_CHARS = 800

    fun buildWithTools(userInput: String, tools: List<Tool>): String {
        val toolBlock = if (tools.isEmpty()) "" else buildString {
            append("\nTools (use when the question requires live data):\n")
            tools.forEach { tool ->
                val params = if (tool.parameters.isEmpty()) "()"
                else "(${tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                append("  ${tool.name}$params → ${tool.description}\n")
            }
            append("To use a tool output ONLY the JSON: {\"tool\":\"name\",\"args\":{}}\n" +
                   "Do NOT use tools for questions you can answer directly.")
        }

        val prefix = "[Vela: on-device AI assistant.$toolBlock\n" +
            "For structured answers you MAY also use vela-ui JSON (card, step, item, tip, code).\n" +
            "Plain text preferred for simple answers.]"

        return "$prefix\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"
    }

    fun build(userInput: String): String =
        "[Vela: on-device AI assistant. Respond concisely.]\n\nUser: ${userInput.take(MAX_USER_CHARS)}\nVela:"
}
```

---

### P1-T3 — Create `MlKitInferenceProvider.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/MlKitInferenceProvider.kt`

```kotlin
package com.vela.app.ai

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InferenceProvider backed by ML Kit Gemma via AICore.
 * LEGACY: Retained as a fallback only. Primary inference is now handled by
 * AmplifierNodeProvider (Claude via Amplifier nodes). This provider is not
 * expected to be the active implementation in production.
 */
@Singleton
class MlKitInferenceProvider @Inject constructor(
    private val engine: MlKitGemma4Engine,
) : InferenceProvider {

    override val name = "mlkit-gemma-legacy"

    override suspend fun isAvailable(): Boolean =
        engine.checkReadiness() == ReadinessState.Available

    override fun streamText(prompt: String): Flow<String> = engine.streamText(prompt)

    override fun shutdown() = engine.shutdown()
}
```

---

### P1-T4 — Create `ProviderRegistry.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/ProviderRegistry.kt`

```kotlin
package com.vela.app.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds an ordered list of InferenceProviders. Returns the first available one.
 * Primary provider is tried first; fallbacks follow in order.
 *
 * Providers are injected in priority order via AppModule (primary first).
 * In Phase 1 only MlKitInferenceProvider is registered.
 * In Phase 2 LlamaCppProvider is prepended as the primary.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val providers: List<InferenceProvider>,
) {
    /** Returns the first available provider, or throws if none are ready. */
    suspend fun current(): InferenceProvider =
        providers.firstOrNull { it.isAvailable() }
            ?: providers.firstOrNull()
            ?: error("No InferenceProviders registered")

    fun all(): List<InferenceProvider> = providers
}
```

---

### P1-T5 — Update `ConversationViewModel.kt`
**Modify**: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`

Replace direct `GemmaEngine` usage with `AgentOrchestrator`. Remove the inline `agentLoop`, `streamAndCollect`, `appendExchange` methods — they live in `AgentOrchestrator` now. Keep all StateFlow UI state.

New constructor: `AgentOrchestrator` instead of `GemmaEngine`. Remove `toolRegistry` from constructor (orchestrator owns it internally). Remove `VelaPromptBuilder`, `ToolCallParser`, `ToolRegistry` imports from ViewModel — they belong to the orchestrator.

```kotlin
// New constructor injection:
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val orchestrator: AgentOrchestrator,
    private val repository: ConversationRepository,
    private val ttsEngine: TtsEngine,
) : ViewModel() { ... }

// processInput delegates fully to orchestrator:
private fun processInput(input: String) {
    viewModelScope.launch {
        repository.saveMessage(Message(role = MessageRole.USER, content = input))
        _isProcessing.value = true
        try {
            val finalText = orchestrator.runLoop(
                userInput = input,
                onTokenChunk = { chunk ->
                    _streamingResponse.value = (_streamingResponse.value ?: "") + chunk
                },
                onToolStart = { toolName ->
                    _streamingResponse.value = null
                    _toolExecutionState.value = toolName
                },
                onToolEnd = {
                    _toolExecutionState.value = null
                },
                onStepChange = { current, max ->
                    _agentStep.value = AgentStep(current = current, max = max)
                },
            )
            if (finalText.isNotEmpty()) {
                ttsEngine.speak(finalText)
                repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = finalText))
            }
        } catch (e: Exception) {
            repository.saveMessage(
                Message(role = MessageRole.ASSISTANT, content = "⚠️ ${buildErrorMessage(e)}")
            )
        } finally {
            _streamingResponse.value = null
            _toolExecutionState.value = null
            _agentStep.value = null
            _isProcessing.value = false
        }
    }
}
```

---

### P1-T6 — Update `AppModule.kt`
**Modify**: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

Add providers for:
- `AgentOrchestrator` (singleton, needs `ProviderRegistry` + `ToolRegistry`)
- `ProviderRegistry` (singleton, list of providers)
- `List<InferenceProvider>` (Phase 1: just `[MlKitInferenceProvider]`)

Remove:
- `provideGemmaEngine` binding (no longer injected into ViewModel)
- Keep `provideLifecycleAwareEngine` for `MlKitGemma4Engine` readiness checks in loading screen

```kotlin
@Provides @Singleton
fun provideInferenceProviders(mlKit: MlKitInferenceProvider): List<InferenceProvider> =
    listOf(mlKit)  // Phase 2: prepend LlamaCppProvider here

@Provides @Singleton
fun provideProviderRegistry(
    providers: @JvmSuppressWildcards List<InferenceProvider>
): ProviderRegistry = ProviderRegistry(providers)

@Provides @Singleton
fun provideAgentOrchestrator(
    registry: ProviderRegistry,
    tools: ToolRegistry,
): AgentOrchestrator = AgentOrchestrator(registry, tools)
```

---

## ~~PHASE 2: llama.cpp JNI Integration~~ — SUPERSEDED

> **This phase is cancelled.** Vela's architecture has shifted from local on-device inference (Gemma 3/4 GGUF via llama.cpp) to Claude via Amplifier nodes on the network. The `InferenceProvider` abstraction from Phase 1 is the extension point — implement `AmplifierNodeProvider` instead of `LlamaCppProvider` as the primary backend. The implementation below is preserved for reference only.

### P2-T1 — Add llama.cpp submodule + CMakeLists.txt

**Run**:
```bash
cd /Users/ken/workspace/vela
git submodule add https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git submodule update --init --recursive
```

**New file**: `app/CMakeLists.txt`
```cmake
cmake_minimum_required(VERSION 3.22.1)
project("vela")

# llama.cpp build options — disable unused backends for smaller APK
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)  # Enable manually if testing Vulkan
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)  # Enable for Adreno GPU support

add_subdirectory(src/main/cpp/llama.cpp)

add_library(vela-llama SHARED src/main/cpp/llama_bridge.cpp)

target_include_directories(vela-llama PRIVATE src/main/cpp/llama.cpp/include)

target_link_libraries(vela-llama
    llama
    ggml
    android
    log
)
```

**Update `app/build.gradle.kts`** — add NDK block inside `android { }`:
```kotlin
externalNativeBuild {
    cmake {
        path = file("CMakeLists.txt")
        version = "3.22.1"
    }
}
ndkVersion = "27.0.12077973"  // use latest stable
```

---

### P2-T2 — Create `llama_bridge.cpp`
**New file**: `app/src/main/cpp/llama_bridge.cpp`

Adapted from SmolChat-Android's `smollm.cpp`. Key JNI functions:
- `Java_com_vela_app_ai_llama_LlamaBridge_nativeLoad` — loads model, returns context ptr
- `Java_com_vela_app_ai_llama_LlamaBridge_nativeCompletion` — runs completion, fires token callback
- `Java_com_vela_app_ai_llama_LlamaBridge_nativeFree` — frees context

Uses llama.cpp C API: `llama_model_load_from_file`, `llama_new_context_with_model`, `llama_tokenize`, `llama_decode`, `llama_sampler_chain_default`, `llama_token_to_piece`.

Applies Gemma chat template via `llama_chat_apply_template` before tokenizing.

Full implementation in `llama_bridge.cpp` (see execution task).

---

### P2-T3 — Create `LlamaBridge.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/llama/LlamaBridge.kt`

```kotlin
package com.vela.app.ai.llama

/**
 * Kotlin JNI declarations for the native llama.cpp bridge.
 * All external functions correspond to Java_com_vela_app_ai_llama_LlamaBridge_* in llama_bridge.cpp.
 */
object LlamaBridge {
    init { System.loadLibrary("vela-llama") }

    /** Load a GGUF model. Returns a non-zero context pointer on success, 0 on failure. */
    external fun nativeLoad(
        modelPath: String,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
    ): Long

    /**
     * Run completion for [prompt]. Calls [tokenCallback] for each generated token.
     * Returns the full generated text.
     */
    external fun nativeCompletion(
        contextPtr: Long,
        prompt: String,
        nPredict: Int = 512,
        tokenCallback: TokenCallback,
    ): String

    /** Free native model + context memory. */
    external fun nativeFree(contextPtr: Long)

    fun interface TokenCallback {
        fun onToken(token: String)
    }
}
```

---

### P2-T4 — Create `LlamaCppProvider.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/llama/LlamaCppProvider.kt`

```kotlin
package com.vela.app.ai.llama

import com.vela.app.ai.InferenceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * InferenceProvider backed by llama.cpp via JNI.
 * Loads any GGUF model file. Applies Gemma chat template before inference.
 * Streams tokens via callback → Flow.
 */
class LlamaCppProvider(
    private val modelFile: File,
    private val nCtx: Int = 4096,
    private val nThreads: Int = 4,
    private val nGpuLayers: Int = 0,
    private val nPredict: Int = 512,
) : InferenceProvider {

    override val name = "llama-cpp-${modelFile.nameWithoutExtension}"

    private var contextPtr: Long = 0L

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) return@withContext
        contextPtr = LlamaBridge.nativeLoad(modelFile.absolutePath, nCtx, nThreads, nGpuLayers)
        check(contextPtr != 0L) { "Failed to load model: ${modelFile.name}" }
    }

    override suspend fun isAvailable(): Boolean =
        modelFile.exists() && contextPtr != 0L

    /**
     * Stream tokens from llama.cpp. Each emission is a raw token string.
     * The AgentOrchestrator accumulates them.
     */
    override fun streamText(prompt: String): Flow<String> = callbackFlow {
        withContext(Dispatchers.IO) {
            LlamaBridge.nativeCompletion(
                contextPtr = contextPtr,
                prompt = prompt,
                nPredict = nPredict,
            ) { token ->
                trySend(token)
            }
        }
        close()
        awaitClose()
    }.flowOn(Dispatchers.IO)

    override fun shutdown() {
        if (contextPtr != 0L) {
            LlamaBridge.nativeFree(contextPtr)
            contextPtr = 0L
        }
    }
}
```

---

### P2-T5 — Create `ModelDownloadManager.kt`
**New file**: `app/src/main/kotlin/com/vela/app/ai/llama/ModelDownloadManager.kt`

~~Downloads Gemma 3 4B IT Q4_K_M GGUF from HuggingFace.~~ **SUPERSEDED** — local model download is no longer part of the architecture. See Phase 2 header.
URL: `https://huggingface.co/bartowski/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf`
Dest: `{filesDir}/models/gemma-3-4b-it-Q4_K_M.gguf`

Exposes:
- `modelFile(): File` — target file path
- `isDownloaded(): Boolean`
- `download(): Flow<DownloadState>` — emits `Progress(bytesRead, totalBytes)` then `Done(file)`

Uses OkHttp with streaming body read, writing to temp file then renaming to final path on success.

```kotlin
sealed class DownloadState {
    data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
```

---

### P2-T6 — Create `ModelDownloadDialog` composable
**New file**: `app/src/main/kotlin/com/vela/app/ui/download/ModelDownloadScreen.kt`

Shown on first launch when `modelManager.isDownloaded()` is false and `LlamaCppProvider` is configured as primary.

UI states:
1. **Confirmation**: ~~"Gemma 3 4B (~2.5 GB) needs to be downloaded to run locally."~~ **SUPERSEDED** — no local model download. This screen is no longer needed.
2. **Downloading**: Progress bar + "2.1 GB / 2.5 GB"
3. **Done**: Dismissed, app proceeds normally

This is a full-screen Composable shown from `MainActivity` before the conversation screen.

---

### P2-T7 — Final wiring in `AppModule.kt` (Phase 2 additions)

```kotlin
// Model paths
@Provides @Singleton
fun provideModelDownloadManager(
    @ApplicationContext context: Context,
    client: OkHttpClient,
): ModelDownloadManager = ModelDownloadManager(context.filesDir, client)

// LlamaCppProvider — not a singleton, loaded lazily after download
@Provides
fun provideLlamaCppProvider(manager: ModelDownloadManager): LlamaCppProvider =
    LlamaCppProvider(modelFile = manager.modelFile())

// Replace Phase 1 provider list:
@Provides @Singleton
fun provideInferenceProviders(
    llamaCpp: LlamaCppProvider,
    mlKit: MlKitInferenceProvider,
): List<InferenceProvider> = listOf(llamaCpp, mlKit)  // llama.cpp primary, ML Kit fallback
```

---

## Task Dependency Graph

```
P1-T1 ──────────────────────────────┐
P1-T2 (needs T1) ──────────────────►│
P1-T3 (needs T1) ──────────────────►├──► P1-T5 (needs T1,T2,T3,T4) ──► P1-T6
P1-T4 (needs T1,T3) ───────────────►│
                                     
P2-T1 (independent) ───────────────►│
P2-T2 (needs T1) ──────────────────►├──► P2-T3 ──► P2-T4 ──► P2-T7
P2-T5 (independent) ───────────────►│
P2-T6 (needs T5) ──────────────────►│
```
