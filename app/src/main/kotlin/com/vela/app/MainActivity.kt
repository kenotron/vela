package com.vela.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.conversation.ConversationRoot
import com.vela.app.ui.conversation.ConversationViewModel
import com.vela.app.ui.theme.VelaTheme
import com.vela.app.voice.SpeechTranscriber
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var speechTranscriber: SpeechTranscriber

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelaTheme {
                val vm: ConversationViewModel = hiltViewModel()
                var showApiKeyDialog by remember { mutableStateOf(!vm.isConfigured) }

                if (showApiKeyDialog) {
                    ApiKeyDialog(
                        onConfirm = { key ->
                            vm.setApiKey(key)
                            showApiKeyDialog = false
                        }
                    )
                } else {
                    ConversationRoot(speechTranscriber = speechTranscriber)
                }
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
