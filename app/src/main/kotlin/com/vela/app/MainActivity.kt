package com.vela.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.NavigationScaffold
import com.vela.app.ui.conversation.ConversationViewModel
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.voice.SpeechTranscriber
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var speechTranscriber: SpeechTranscriber

    private val conversationViewModel: ConversationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenConversationIntent(intent)
        addOnNewIntentListener { handleOpenConversationIntent(it) }
        setContent {
            VelaTheme {
                val vm: ConversationViewModel = hiltViewModel()
                var showApiKeyDialog by remember { mutableStateOf(!vm.isConfigured) }

                @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)

                if (showApiKeyDialog) {
                    ApiKeyDialog(onConfirm = { key -> vm.setApiKey(key); showApiKeyDialog = false })
                } else {
                    NavigationScaffold(
                        windowSizeClass = windowSizeClass,
                        speechTranscriber = speechTranscriber,
                    )
                }
            }
        }
    }

    private fun handleOpenConversationIntent(intent: Intent?) {
        if (intent?.action == "com.vela.app.OPEN_CONVERSATION") {
            val convId = intent.getStringExtra("conversationId") ?: return
            val message = intent.getStringExtra("stagedMessage")
            conversationViewModel.switchToConversation(convId)
            if (!message.isNullOrBlank()) {
                conversationViewModel.setPendingInput(message)
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(onConfirm: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Anthropic API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your Anthropic API key to use Vela. " +
                    "Get one at console.anthropic.com.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("sk-ant-\u2026") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (key.isNotBlank()) onConfirm(key.trim()) },
                enabled = key.isNotBlank(),
            ) { Text("Save") }
        },
    )
}
