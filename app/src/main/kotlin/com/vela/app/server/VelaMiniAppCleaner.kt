package com.vela.app.server

import android.content.Context
import android.util.Log
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VelaMiniAppCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
) {
    companion object {
        private const val PREFS = "amplifier_prefs"
        private const val KEY   = "renderers_v2_cleared"
        private const val TAG   = "VelaMiniAppCleaner"
    }

    private val prefs = context.getSharedPreferences(PREFS, 0)

    fun clearStaleRenderersIfNeeded() {
        if (prefs.getBoolean(KEY, false)) {
            Log.d(TAG, "v2 migration already done — skipping")
            return
        }
        var deletedCount = 0
        vaultRegistry.enabledVaults.value.forEach { vault ->
            val renderersDir = File(vault.localPath, ".vela/renderers")
            renderersDir.listFiles()?.forEach { typeDir ->
                val html = File(typeDir, "renderer.html")
                if (html.exists() && html.delete()) {
                    deletedCount++
                    Log.i(TAG, "Deleted stale renderer: ${html.absolutePath}")
                }
            }
        }
        prefs.edit().putBoolean(KEY, true).apply()
        Log.i(TAG, "v2 migration complete — deleted $deletedCount stale renderer(s)")
    }
}
