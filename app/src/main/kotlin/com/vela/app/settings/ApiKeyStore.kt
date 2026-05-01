package com.vela.app.settings

    import android.content.Context
    import androidx.security.crypto.EncryptedSharedPreferences
    import androidx.security.crypto.MasterKey
    import dagger.hilt.android.qualifiers.ApplicationContext
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Encrypted key-value store for sensitive API credentials.
     *
     * Uses EncryptedSharedPreferences backed by AES-256-GCM/SIV so that keys are
     * never stored in plaintext on disk. The master key lives in the Android Keystore.
     *
     * Supported keys:
     *  - OPENAI_API_KEY  — used locally by WhisperClient for voice transcription
     *
     * Note: ANTHROPIC_API_KEY is NOT stored here. It lives on the remote node inside
     * the launchd/systemd service file and is entered once during "Connect a Node".
     */
    @Singleton
    class ApiKeyStore @Inject constructor(@ApplicationContext ctx: Context) {

        private val prefs = EncryptedSharedPreferences.create(
            ctx,
            "vela_api_keys",
            MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        var openAiKey: String
            get() = prefs.getString("OPENAI_API_KEY", "") ?: ""
            set(v) = prefs.edit().putString("OPENAI_API_KEY", v).apply()
    }
    