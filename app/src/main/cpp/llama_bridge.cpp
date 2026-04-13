
    #include <jni.h>
    #include <string>
    #include <vector>
    #include <android/log.h>
    #include "llama.h"

    #define TAG "VelaLlama"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

    struct LlamaCtx {
        llama_model*   model = nullptr;
        llama_context* ctx   = nullptr;
    };

    extern "C" {

    // ─── Load ─────────────────────────────────────────────────────────────────────

    JNIEXPORT jlong JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeLoad(
            JNIEnv* env, jobject,
            jstring jModelPath, jint nCtx, jint nThreads, jint nGpuLayers) {

        llama_backend_init();

        const char* path = env->GetStringUTFChars(jModelPath, nullptr);
        LOGI("Loading: %s  ctx=%d  threads=%d  gpu=%d", path, (int)nCtx, (int)nThreads, (int)nGpuLayers);

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = (int)nGpuLayers;

        llama_model* model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(jModelPath, path);
        if (!model) { LOGE("Failed to load model"); return 0L; }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx            = (uint32_t)nCtx;
        cparams.n_threads        = (int32_t)nThreads;
        cparams.n_threads_batch  = (int32_t)nThreads;
        cparams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_AUTO;

        llama_context* ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("Failed to create context");
            llama_model_free(model);
            return 0L;
        }

        LOGI("Model loaded OK");
        return reinterpret_cast<jlong>(new LlamaCtx{model, ctx});
    }

    // ─── Completion ───────────────────────────────────────────────────────────────

    JNIEXPORT jstring JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeCompletion(
            JNIEnv* env, jobject,
            jlong ctxPtr, jstring jPrompt, jint nPredict, jobject tokenCallback) {

        auto* lctx = reinterpret_cast<LlamaCtx*>(ctxPtr);
        if (!lctx || !lctx->ctx) return env->NewStringUTF("Error: model not loaded");

        // ── Use the raw prompt directly — no chat template ────────────────────────
        // PromptBuilder already produces a structured role-based text prompt that
        // works exactly like the ML Kit path. Applying llama_chat_apply_template on
        // top wraps the entire growing history (including "Vela:" role markers and
        // tool exchange lines) as a single <user> turn, which confuses the model and
        // breaks tool call detection. Tokenise the raw text instead.
        const char* rawPrompt = env->GetStringUTFChars(jPrompt, nullptr);
        std::string prompt(rawPrompt);
        env->ReleaseStringUTFChars(jPrompt, rawPrompt);

        // ── Tokenise ──────────────────────────────────────────────────────────────
        const llama_vocab* vocab = llama_model_get_vocab(lctx->model);

        // First pass: measure required token count
        int nTok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
        if (nTok < 0) nTok = -nTok;  // negative return = required buffer size

        std::vector<llama_token> tokens(nTok);
        nTok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                              tokens.data(), (int)tokens.size(),
                              /*add_special=*/true, /*parse_special=*/true);
        if (nTok <= 0) {
            LOGE("Tokenisation failed");
            return env->NewStringUTF("Error: tokenisation failed");
        }
        tokens.resize(nTok);
        LOGI("Prompt tokens: %d", nTok);

        // ── Clear KV cache + prefill ──────────────────────────────────────────────
        llama_memory_clear(llama_get_memory(lctx->ctx), /*data=*/false);

        llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
        if (llama_decode(lctx->ctx, batch) != 0) {
            LOGE("Prefill decode failed");
            return env->NewStringUTF("Error: prefill failed");
        }

        // ── Sampler ───────────────────────────────────────────────────────────────
        llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.8f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // ── Token callback (Kotlin streaming) ────────────────────────────────────
        jclass    cbClass = env->GetObjectClass(tokenCallback);
        jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

        // ── Decode loop ───────────────────────────────────────────────────────────
        std::string result;
        result.reserve(512);
        char piece[256];

        for (int i = 0; i < (int)nPredict; ++i) {
            llama_token tok = llama_sampler_sample(sampler, lctx->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;

            int pLen = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1,
                                            0, false);
            if (pLen <= 0) break;
            piece[pLen] = '\0';
            result += piece;

            jstring jPiece = env->NewStringUTF(piece);
            env->CallVoidMethod(tokenCallback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);

            // Stop generating if we hit a newline after a JSON closing brace —
            // that's the tool call complete, no need to generate further prose.
            if (result.size() > 2 &&
                result.back() == '\n' &&
                result[result.size()-2] == '}') {
                break;
            }

            llama_batch next = llama_batch_get_one(&tok, 1);
            if (llama_decode(lctx->ctx, next) != 0) break;
        }

        llama_sampler_free(sampler);
        LOGI("Generated %zu chars", result.size());
        return env->NewStringUTF(result.c_str());
    }

    // ─── Free ─────────────────────────────────────────────────────────────────────

    JNIEXPORT void JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeFree(
            JNIEnv*, jobject, jlong ctxPtr) {

        auto* lctx = reinterpret_cast<LlamaCtx*>(ctxPtr);
        if (!lctx) return;
        llama_free(lctx->ctx);
        llama_model_free(lctx->model);
        delete lctx;
        llama_backend_free();
        LOGI("Model freed");
    }

    } // extern "C"
    