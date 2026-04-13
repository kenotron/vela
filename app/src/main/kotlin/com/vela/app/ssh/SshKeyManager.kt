    package com.vela.app.ssh

    import android.content.Context
    import android.content.SharedPreferences
    import android.util.Log
    import com.jcraft.jsch.JSch
    import com.jcraft.jsch.KeyPair
    import java.io.ByteArrayOutputStream
    import javax.inject.Inject
    import javax.inject.Singleton

    private const val TAG  = "SshKeyManager"
    private const val PREF = "vela_ssh_keys"
    private const val KEY_PRIV = "private_key_pem"
    private const val KEY_PUB  = "public_key_ossh"

    /**
     * Manages the device’s single Ed25519 SSH identity used by all Vela node connections.
     *
     * Keys are generated on first access and stored in a private SharedPreferences file.
     * The public key is in OpenSSH wire format (“ssh-rsa AAAA… vela@android”) —
     * the user copies it into ~/.ssh/authorized_keys on each remote machine.
     */
    @Singleton
    class SshKeyManager @Inject constructor(
        private val context: Context,
    ) {
        private val prefs: SharedPreferences
            get() = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // ── Public API ─────────────────────────────────────────────────────────────

        /** Human-readable OpenSSH public key line to paste into authorized_keys. */
        fun getPublicKey(): String {
            ensureKeyExists()
            return prefs.getString(KEY_PUB, "") ?: ""
        }

        /** Raw private key PEM (PKCS#8 / OpenSSH private key block). */
        fun getPrivateKeyPem(): String {
            ensureKeyExists()
            return prefs.getString(KEY_PRIV, "") ?: ""
        }

        /** Delete the key pair — next call to [getPublicKey] regenerates. */
        fun reset() = prefs.edit().remove(KEY_PRIV).remove(KEY_PUB).apply()

        // ── Key generation ─────────────────────────────────────────────────────────

        private fun ensureKeyExists() {
            if (prefs.contains(KEY_PRIV) && prefs.contains(KEY_PUB)) return
            Log.i(TAG, "Generating new RSA-3072 key pair for Vela node identity…")
            try {
                val jsch = JSch()
                val kp   = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
                kp.setPassphrase("") // never encrypted on-device; the device IS the secret

                val privOut = ByteArrayOutputStream()
                kp.writePrivateKey(privOut)

                val pubOut = ByteArrayOutputStream()
                kp.writePublicKey(pubOut, "vela@android")

                prefs.edit()
                    .putString(KEY_PRIV, String(privOut.toByteArray(), Charsets.UTF_8))
                    .putString(KEY_PUB,  String(pubOut.toByteArray(),  Charsets.UTF_8).trim())
                    .apply()

                Log.i(TAG, "Key pair generated successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Key generation failed", e)
            }
        }
    }
    