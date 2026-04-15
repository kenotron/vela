package com.vela.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.vela.app.MainActivity
import com.vela.app.ui.theme.VelaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareReceiveActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            VelaTheme {
                ShareProcessingScreen(
                    onDone = { conversationId ->
                        val launch = Intent(this, MainActivity::class.java).apply {
                            action = "com.vela.app.OPEN_CONVERSATION"
                            putExtra("conversationId", conversationId)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(launch)
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        val sourceApp = intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: callingActivity?.packageName

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: ""
                when {
                    mimeType.startsWith("text/") -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                        if (text.isNotBlank()) viewModel.prepareText(text, sourceApp)
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) viewModel.prepareFile(uri, mimeType, sourceApp)
                    }
                }
            }
        }
    }
}
