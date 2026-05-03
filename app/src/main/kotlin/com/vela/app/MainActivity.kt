package com.vela.app

    import android.Manifest
    import android.content.Intent
    import android.os.Build
    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.enableEdgeToEdge
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.activity.viewModels
    import com.vela.app.streaming.SessionStreamingService
    import com.vela.app.ui.navigation.DeepLinkViewModel
    import com.vela.app.ui.navigation.Routes
    import com.vela.app.ui.navigation.VelaApp
    import com.vela.app.ui.theme.VelaTheme
    import dagger.hilt.android.AndroidEntryPoint

    @AndroidEntryPoint
    class MainActivity : ComponentActivity() {

        private val deepLinkVm: DeepLinkViewModel by viewModels()

        private val notificationPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* granted / denied — ApprovalNotificationHelper checks the permission at post-time */ }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            requestNotificationPermissionIfNeeded()
            startForegroundService(Intent(this, SessionStreamingService::class.java))
            processDeepLinkIntent(intent)
            setContent {
                VelaTheme {
                    VelaApp()
                }
            }
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            processDeepLinkIntent(intent)
        }

        private fun processDeepLinkIntent(intent: Intent?) {
            val nodeId    = intent?.getStringExtra("deep_link_node_id")    ?: return
            val sessionId = intent.getStringExtra("deep_link_session_id") ?: return
            deepLinkVm.navigate(Routes.sessionDetail(nodeId, sessionId))
        }

        private fun requestNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    