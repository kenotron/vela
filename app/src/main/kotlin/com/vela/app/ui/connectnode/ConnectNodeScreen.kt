package com.vela.app.ui.connectnode

    import android.content.ClipData
    import android.content.ClipboardManager
    import android.content.Context
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.Row
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.imePadding
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.lazy.LazyRow
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.rememberScrollState
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.text.KeyboardOptions
    import androidx.compose.foundation.verticalScroll
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.filled.ContentCopy
    import androidx.compose.material.icons.filled.Visibility
    import androidx.compose.material.icons.filled.VisibilityOff
    import androidx.compose.material3.Button
    import androidx.compose.material3.ButtonDefaults
    import androidx.compose.material3.ExperimentalMaterial3Api
    import androidx.compose.material3.FilterChip
    import androidx.compose.material3.FilterChipDefaults
    import androidx.compose.material3.SuggestionChip
    import androidx.compose.material3.SuggestionChipDefaults
    import androidx.compose.material3.Icon
    import androidx.compose.material3.IconButton
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.ModalBottomSheet
    import androidx.compose.material3.OutlinedTextField
    import androidx.compose.material3.OutlinedTextFieldDefaults
    import androidx.compose.material3.Scaffold
    import androidx.compose.material3.Text
    import androidx.compose.material3.TopAppBar
    import androidx.compose.material3.TopAppBarDefaults
    import androidx.compose.material3.rememberModalBottomSheetState
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.LaunchedEffect
    import androidx.compose.runtime.collectAsState
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.setValue
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.input.ImeAction
    import androidx.compose.ui.text.input.KeyboardType
    import androidx.compose.ui.text.input.PasswordVisualTransformation
    import androidx.compose.ui.text.input.VisualTransformation
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.ssh.BundleChoice
    import com.vela.app.ui.connectors.NodeBootstrapSheet
    import com.vela.app.ui.theme.VelaColors

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ConnectNodeScreen(
        onNavigateBack: () -> Unit,
        onConnected:    () -> Unit,
        viewModel:      ConnectNodeViewModel = hiltViewModel(),
    ) {
        val form           by viewModel.form.collectAsState()
        val bootstrapState by viewModel.bootstrapState.collectAsState()

        val context       = LocalContext.current
        val sheetState    = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var apiKeyVisible by remember { mutableStateOf(false) }

        // Show bootstrap sheet whenever bootstrapping or complete
        val showSheet = bootstrapState.isBootstrapping || bootstrapState.isComplete

        // Navigate back automatically when bootstrap completes and user closes sheet
        LaunchedEffect(bootstrapState.isComplete) {
            if (bootstrapState.isComplete) {
                viewModel.clearBootstrapState()
                onConnected()
            }
        }

        Scaffold(
            containerColor = VelaColors.Abyss,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint               = VelaColors.Accent,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = VelaColors.Abyss,
                    ),
                )
            },
        ) { innerPadding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {

                // ── Hero ─────────────────────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "Connect a node.",
                    style = MaterialTheme.typography.displayMedium,
                    color = VelaColors.TextPrimary,
                )
                Text(
                    text  = "Enter the address of an amplifierd instance on your network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VelaColors.TextSecondary,
                )

                // ── SSH credentials form ──────────────────────────────────────────
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = VelaColors.Accent,
                    unfocusedBorderColor = VelaColors.SurfaceRaised,
                    focusedLabelColor    = VelaColors.Accent,
                    unfocusedLabelColor  = VelaColors.TextSecondary,
                    cursorColor          = VelaColors.Accent,
                )
                val fieldShape = RoundedCornerShape(16.dp)

                OutlinedTextField(
                    value         = form.host,
                    onValueChange = viewModel::updateHost,
                    label         = { Text("Host") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = fieldShape,
                    colors        = fieldColors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                val recentHosts = viewModel.recentHosts
                if (recentHosts.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recentHosts) { host ->
                            SuggestionChip(
                                onClick = { viewModel.updateHost(host) },
                                label   = { Text(host, style = MaterialTheme.typography.labelMedium, color = VelaColors.TextSecondary) },
                                shape   = RoundedCornerShape(8.dp),
                                colors  = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = VelaColors.SurfaceRaised,
                                ),
                                border  = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled             = true,
                                    borderColor         = VelaColors.StrokeHair,
                                    disabledBorderColor = VelaColors.StrokeHair,
                                ),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value         = form.port,
                    onValueChange = viewModel::updatePort,
                    label         = { Text("Port") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = fieldShape,
                    colors        = fieldColors,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction    = ImeAction.Next,
                    ),
                )

                OutlinedTextField(
                    value         = form.username,
                    onValueChange = viewModel::updateUsername,
                    label         = { Text("Username") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = fieldShape,
                    colors        = fieldColors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                // Public key field (read-only) + copy button
                OutlinedTextField(
                    value         = viewModel.publicKey,
                    onValueChange = {},
                    label         = { Text("Public key") },
                    readOnly      = true,
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = fieldShape,
                    colors        = fieldColors,
                    trailingIcon  = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Vela public key", viewModel.publicKey)
                            )
                        }) {
                            Icon(
                                imageVector        = Icons.Default.ContentCopy,
                                contentDescription = "Copy public key",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                    },
                )

                Text(
                    text  = "Paste this into ~/.ssh/authorized_keys on the node, then tap Continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VelaColors.TextSecondary,
                )

                // ── Workspace directory ────────────────────────────────────────────────
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value         = form.workspaceDir,
                    onValueChange = { viewModel.updateWorkspaceDir(it) },
                    label         = { Text("Workspace directory", color = VelaColors.TextSecondary) },
                    placeholder   = { Text("~/workspace", color = VelaColors.TextTertiary) },
                    singleLine    = true,
                    supportingText = {
                        Text(
                            "All projects on this node will run sessions here",
                            color = VelaColors.TextTertiary,
                            fontSize = 11.sp,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = VelaColors.Accent,
                        unfocusedBorderColor = VelaColors.StrokeEdge,
                        focusedTextColor     = VelaColors.TextPrimary,
                        unfocusedTextColor   = VelaColors.TextPrimary,
                        cursorColor          = VelaColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Bundle selection chips ────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BundleChoice.entries.forEach { choice ->
                        val isSelected = form.bundle == choice
                        FilterChip(
                            selected = isSelected,
                            onClick  = { viewModel.updateBundle(choice) },
                            label    = {
                                Text(
                                    text       = choice.name.replace("_", " "),
                                    style      = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            shape  = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VelaColors.Accent,
                                selectedLabelColor     = VelaColors.Abyss,
                                containerColor         = VelaColors.SurfaceRaised,
                                labelColor             = VelaColors.TextSecondary,
                            ),
                        )
                    }
                }

                // ── API key field ─────────────────────────────────────────────────
                OutlinedTextField(
                    value         = form.anthropicKey,
                    onValueChange = viewModel::updateApiKey,
                    label         = { Text("ANTHROPIC_API_KEY") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = fieldShape,
                    colors        = fieldColors,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector        = if (apiKeyVisible) Icons.Default.VisibilityOff
                                                     else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide key" else "Show key",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                    },
                )

                // ── Connect button ────────────────────────────────────────────────
                Button(
                    onClick  = { viewModel.connect() },
                    enabled  = form.host.isNotBlank() && form.username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape  = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text       = "CONNECT",
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(96.dp)) // clear Voice FAB
            }

            // ── Bootstrap progress sheet ──────────────────────────────────────────
            if (showSheet) {
                ModalBottomSheet(
                    onDismissRequest = {
                        val wasComplete = bootstrapState.isComplete
                        viewModel.clearBootstrapState()
                        if (wasComplete) onConnected()
                    },
                    sheetState     = sheetState,
                    containerColor = VelaColors.SurfaceRaised,
                    dragHandle     = null,
                ) {
                    NodeBootstrapSheet(
                        state     = bootstrapState,
                        onDismiss = {
                            val wasComplete = bootstrapState.isComplete
                            viewModel.clearBootstrapState()
                            if (wasComplete) onConnected()
                        },
                    )
                }
            }
        }
    }
    