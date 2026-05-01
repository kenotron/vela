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
 *  - ANTHROPIC_API_KEY  — used by amplifierd on the node (forwarded via session create)
 *  - OPENAI_API_KEY     — used locally by WhisperClient for voice transcription
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

    var anthropicKey: String
        get() = prefs.getString("ANTHROPIC_API_KEY", "") ?: ""
        set(v) = prefs.edit().putString("ANTHROPIC_API_KEY", v).apply()

    var openAiKey: String
        get() = prefs.getString("OPENAI_API_KEY", "") ?: ""
        set(v) = prefs.edit().putString("OPENAI_API_KEY", v).apply()
}
