package com.vela.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.vela.app.streaming.SessionStreamingService
import com.vela.app.ui.navigation.VelaApp
import com.vela.app.ui.theme.VelaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Requests POST_NOTIFICATIONS on Android 13+. We degrade gracefully if denied. */
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted / denied — ApprovalNotificationHelper checks the permission at post-time */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        // Start the streaming service from the Activity so Android allows startForegroundService()
        // (Android 12+ blocks FGS launch from Application.onCreate() background state).
        startForegroundService(Intent(this, SessionStreamingService::class.java))
        setContent {
            VelaTheme {
                VelaApp()
            }
        }
    }

    /** On Android 13+, ask for POST_NOTIFICATIONS so approval alerts work. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
