
    #include <jni.h>
    #include <string>
    #include <vector>
    #include <android/log.h>
    #include "llama.h"

    #define TAG "VelaLlama"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

    // Bundled model + context pointers so we only allocate once per load.
    struct LlamaCtx {
        llama_model*   model = nullptr;
        llama_context* ctx   = nullptr;
    };

    extern "C" {

    // ─── Load ─────────────────────────────────────────────────────────────────────

    JNIEXPORT jlong JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeLoad(
            JNIEnv* env, jobject /*this*/,
            jstring jModelPath, jint nCtx, jint nThreads, jint nGpuLayers) {

        llama_backend_init();

        const char* path = env->GetStringUTFChars(jModelPath, nullptr);
        LOGI("Loading model: %s  ctx=%d  threads=%d  gpu_layers=%d",
             path, (int)nCtx, (int)nThreads, (int)nGpuLayers);

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = (int)nGpuLayers;

        llama_model* model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(jModelPath, path);

        if (!model) {
            LOGE("Failed to load model");
            return 0L;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx            = (uint32_t)nCtx;
        cparams.n_threads        = (int32_t)nThreads;
        cparams.n_threads_batch  = (int32_t)nThreads;
        cparams.flash_attn       = true;   // faster on mobile when supported

        llama_context* ctx = llama_new_context_with_model(model, cparams);
        if (!ctx) {
            LOGE("Failed to create context");
            llama_model_free(model);
            return 0L;
        }

        LOGI("Model loaded OK");
        auto* lctx = new LlamaCtx{model, ctx};
        return reinterpret_cast<jlong>(lctx);
    }

    // ─── Completion ───────────────────────────────────────────────────────────────

    JNIEXPORT jstring JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeCompletion(
            JNIEnv* env, jobject /*this*/,
            jlong ctxPtr, jstring jPrompt, jint nPredict, jobject tokenCallback) {

        auto* lctx = reinterpret_cast<LlamaCtx*>(ctxPtr);
        if (!lctx || !lctx->ctx) {
            return env->NewStringUTF("Error: model not loaded");
        }

        const char* rawPrompt = env->GetStringUTFChars(jPrompt, nullptr);

        // ── Apply the model's built-in chat template (Gemma / Qwen / Llama 3 etc.) ──
        // This reads the template from the GGUF file so it works for any model.
        llama_chat_message chatMsg = {"user", rawPrompt};
        std::vector<char> formatted(8192);
        int fmtLen = llama_chat_apply_template(
                lctx->model, /*tmpl=*/nullptr,
                &chatMsg, /*n_msg=*/1,
                /*add_ass=*/true,
                formatted.data(), (int)formatted.size());
        env->ReleaseStringUTFChars(jPrompt, rawPrompt);

        std::string prompt;
        if (fmtLen > 0 && fmtLen < (int)formatted.size()) {
            prompt = std::string(formatted.data(), fmtLen);
        } else {
            // Template not available / buffer too small — use raw prompt.
            const char* raw2 = env->GetStringUTFChars(jPrompt, nullptr);
            prompt = std::string(raw2);
            env->ReleaseStringUTFChars(jPrompt, raw2);
        }

        // ── Tokenise ──────────────────────────────────────────────────────────────
        const llama_vocab* vocab = llama_model_get_vocab(lctx->model);
        std::vector<llama_token> tokens(prompt.size() + 16);
        int nTok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                  tokens.data(), (int)tokens.size(),
                                  /*add_special=*/true, /*parse_special=*/true);
        if (nTok < 0) {
            LOGE("Tokenisation failed");
            return env->NewStringUTF("Error: tokenisation failed");
        }
        tokens.resize(nTok);

        // ── Prefill ───────────────────────────────────────────────────────────────
        llama_kv_cache_clear(lctx->ctx);
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

        // ── Token callback ────────────────────────────────────────────────────────
        // Get the onToken(String) method from the TokenCallback interface at runtime.
        jclass  cbClass    = env->GetObjectClass(tokenCallback);
        jmethodID onToken  = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

        // ── Decode loop ───────────────────────────────────────────────────────────
        std::string result;
        result.reserve(512);
        char piece[256];

        for (int i = 0; i < (int)nPredict; ++i) {
            llama_token tok = llama_sampler_sample(sampler, lctx->ctx, -1);

            if (llama_vocab_is_eog(vocab, tok)) break;

            int pLen = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece) - 1,
                                            /*special=*/0, /*render_special_tokens=*/false);
            if (pLen <= 0) break;
            piece[pLen] = '\0';
            result += piece;

            // Emit token to Kotlin
            jstring jPiece = env->NewStringUTF(piece);
            env->CallVoidMethod(tokenCallback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);

            // Decode the newly generated token so it becomes the next KV entry.
            llama_batch next = llama_batch_get_one(&tok, 1);
            if (llama_decode(lctx->ctx, next) != 0) break;
        }

        llama_sampler_free(sampler);
        return env->NewStringUTF(result.c_str());
    }

    // ─── Free ─────────────────────────────────────────────────────────────────────

    JNIEXPORT void JNICALL
    Java_com_vela_app_ai_llama_LlamaBridge_nativeFree(
            JNIEnv* /*env*/, jobject /*this*/, jlong ctxPtr) {

        auto* lctx = reinterpret_cast<LlamaCtx*>(ctxPtr);
        if (!lctx) return;
        llama_free(lctx->ctx);
        llama_model_free(lctx->model);
        delete lctx;
        llama_backend_free();
        LOGI("Model freed");
    }

    } // extern "C"
    