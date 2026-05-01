package com.vela.app.ui.settings

    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.text.KeyboardOptions
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.filled.Visibility
    import androidx.compose.material.icons.filled.VisibilityOff
    import androidx.compose.material3.Button
    import androidx.compose.material3.ButtonDefaults
    import androidx.compose.material3.ExperimentalMaterial3Api
    import androidx.compose.material3.Icon
    import androidx.compose.material3.IconButton
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.OutlinedTextField
    import androidx.compose.material3.OutlinedTextFieldDefaults
    import androidx.compose.material3.Scaffold
    import androidx.compose.material3.Text
    import androidx.compose.material3.TopAppBar
    import androidx.compose.material3.TopAppBarDefaults
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.collectAsState
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.setValue
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.text.input.KeyboardType
    import androidx.compose.ui.text.input.PasswordVisualTransformation
    import androidx.compose.ui.text.input.VisualTransformation
    import androidx.compose.ui.unit.dp
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.ui.theme.VelaColors

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ApiKeySettingsScreen(
        onNavigateBack: () -> Unit,
        viewModel: ApiKeySettingsViewModel = hiltViewModel(),
    ) {
        val openAiKey by viewModel.openAiKey.collectAsState()
        var showOpenAi by remember { mutableStateOf(false) }

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = VelaColors.Accent,
            unfocusedBorderColor = VelaColors.StrokeHair,
            focusedLabelColor    = VelaColors.Accent,
            unfocusedLabelColor  = VelaColors.TextTertiary,
            cursorColor          = VelaColors.Accent,
            focusedTextColor     = VelaColors.TextPrimary,
            unfocusedTextColor   = VelaColors.TextPrimary,
        )

        Scaffold(
            containerColor = VelaColors.Abyss,
            topBar = {
                TopAppBar(
                    title = { Text("API Keys", color = VelaColors.TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint               = VelaColors.Accent,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = VelaColors.Abyss),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value         = openAiKey,
                    onValueChange = viewModel::updateOpenAi,
                    label         = { Text("OPENAI_API_KEY") },
                    supportingText = {
                        Text("Used for Whisper voice transcription", color = VelaColors.TextTertiary)
                    },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    visualTransformation = if (showOpenAi) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon  = {
                        IconButton(onClick = { showOpenAi = !showOpenAi }) {
                            Icon(
                                imageVector        = if (showOpenAi) Icons.Default.VisibilityOff
                                                     else Icons.Default.Visibility,
                                contentDescription = if (showOpenAi) "Hide" else "Show",
                                tint               = VelaColors.TextTertiary,
                            )
                        }
                    },
                    colors = fieldColors,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text  = "Your Anthropic API key lives on the node itself — enter it once in Connect a Node and it's stored in the service file on that machine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VelaColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick  = { viewModel.save(); onNavigateBack() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(26.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text("SAVE")
                }
            }
        }
    }
    