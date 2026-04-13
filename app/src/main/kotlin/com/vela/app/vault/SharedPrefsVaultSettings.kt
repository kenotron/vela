package com.vela.app.vault

import android.content.Context

class SharedPrefsVaultSettings(private val context: Context) : VaultSettings {
    private val prefs
        get() = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    override fun getRemoteUrl(vaultId: String): String =
        prefs.getString("${vaultId}_remote_url", "") ?: ""

    override fun setRemoteUrl(vaultId: String, url: String) {
        prefs.edit().putString("${vaultId}_remote_url", url).apply()
    }

    override fun getPat(vaultId: String): String =
        prefs.getString("${vaultId}_pat", "") ?: ""

    override fun setPat(vaultId: String, pat: String) {
        prefs.edit().putString("${vaultId}_pat", pat).apply()
    }

    override fun getBranch(vaultId: String): String =
        prefs.getString("${vaultId}_branch", "main") ?: "main"

    override fun setBranch(vaultId: String, branch: String) {
        prefs.edit().putString("${vaultId}_branch", branch).apply()
    }
}
