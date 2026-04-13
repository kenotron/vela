package com.vela.app.vault

interface VaultSettings {
    fun getRemoteUrl(vaultId: String): String
    fun setRemoteUrl(vaultId: String, url: String)
    fun getPat(vaultId: String): String
    fun setPat(vaultId: String, pat: String)
    fun getBranch(vaultId: String): String
    fun setBranch(vaultId: String, branch: String)
    /** True only if both remote URL and PAT are configured for this vault. */
    fun isConfiguredForSync(vaultId: String): Boolean =
        getRemoteUrl(vaultId).isNotBlank() && getPat(vaultId).isNotBlank()
}
