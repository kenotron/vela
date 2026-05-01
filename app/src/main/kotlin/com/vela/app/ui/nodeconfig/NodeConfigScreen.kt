package com.vela.app.ui.nodeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigScreen(
    nodeId:         String,
    onNavigateBack: () -> Unit,
    viewModel:      NodeConfigViewModel = hiltViewModel(),
) {
    val tools       by viewModel.tools.collectAsState()
    val maxSteps    by viewModel.maxSteps.collectAsState()
    val isPushing   by viewModel.isPushing.collectAsState()
    val connForm    by viewModel.connForm.collectAsState()
    val isSavingConn by viewModel.isSavingConn.collectAsState()

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text  = viewModel.nodeId,
                            style = MaterialTheme.typography.titleLarge,
                            color = VelaColors.TextPrimary,
                        )
                        Text(
                            text  = "Configuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = VelaColors.TextSecondary,
                        )
                    }
                },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Connection section ────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("CONNECTION")
                Spacer(Modifier.height(8.dp))
                VelaTextField("Label", connForm.label, viewModel::updateLabel)
                Spacer(Modifier.height(8.dp))
                VelaTextField("Host", connForm.host, viewModel::updateHost)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VelaTextField(
                        label         = "Port",
                        value         = connForm.port,
                        onValueChange = viewModel::updatePort,
                        modifier      = Modifier.width(100.dp),
                    )
                    VelaTextField(
                        label         = "Username",
                        value         = connForm.username,
                        onValueChange = viewModel::updateUsername,
                        modifier      = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                VelaTextField(
                    label         = "Workspace directory",
                    value         = connForm.workspaceDir,
                    onValueChange = viewModel::updateWorkspaceDir,
                    placeholder   = "~/workspace",
                    supportingText = "Sessions run here",
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick  = { viewModel.saveConnection() },
                    enabled  = !isSavingConn && connForm.host.isNotBlank() && connForm.username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = VelaColors.SurfacePeak),
                ) {
                    Text(
                        text  = if (isSavingConn) "Saving…" else "SAVE CHANGES",
                        color = VelaColors.Accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // ── Bundle section ────────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("BUNDLE")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "Active bundle",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = VelaColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = VelaColors.SurfaceRaised,
                    ) {
                        Row(
                            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text  = "superpowers",
                                style = MaterialTheme.typography.labelMedium,
                                color = VelaColors.TextPrimary,
                            )
                            Icon(
                                imageVector        = Icons.Default.ChevronRight,
                                contentDescription = "Change bundle",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            // ── Tools section ─────────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("TOOLS")
                Spacer(Modifier.height(8.dp))
                tools.entries.toList().forEach { (toolName, enabled) ->
                    ToolToggleRow(
                        name     = toolName,
                        enabled  = enabled,
                        onToggle = { viewModel.toggleTool(toolName) },
                    )
                }
            }

            // ── Limits section ────────────────────────────────────────────────
            ConfigSectionCard {
                SectionEyebrow("LIMITS")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "Max steps / session",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = VelaColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text       = maxSteps.toString(),
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color      = VelaColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Slider(
                    value         = maxSteps.toFloat(),
                    onValueChange = { viewModel.setMaxSteps(it.toInt()) },
                    valueRange    = 1f..50f,
                    steps         = 48,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = SliderDefaults.colors(
                        thumbColor         = VelaColors.Accent,
                        activeTrackColor   = VelaColors.Accent,
                        inactiveTrackColor = VelaColors.SurfaceRaised,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Push button / progress ────────────────────────────────────────
            if (isPushing) {
                LinearProgressIndicator(
                    modifier   = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(vertical = 16.dp),
                    color      = VelaColors.Accent,
                    trackColor = VelaColors.SurfaceRaised,
                    strokeCap  = StrokeCap.Round,
                )
            } else {
                Button(
                    onClick  = { viewModel.pushToNode() },
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
                        text       = "PUSH TO NODE",
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Shared card container ──────────────────────────────────────────────────────

@Composable
internal fun ConfigSectionCard(
    modifier: Modifier = Modifier,
    content:  @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

// ── Section eyebrow label ──────────────────────────────────────────────────────

@Composable
internal fun SectionEyebrow(label: String) {
    Text(
        text          = label,
        style         = MaterialTheme.typography.labelSmall,
        color         = VelaColors.TextTertiary,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}

// ── Reusable text field ────────────────────────────────────────────────────────

@Composable
private fun VelaTextField(
    label:         String,
    value:         String,
    onValueChange: (String) -> Unit,
    modifier:      Modifier = Modifier.fillMaxWidth(),
    placeholder:   String = "",
    supportingText: String? = null,
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = VelaColors.TextSecondary) },
        placeholder   = if (placeholder.isNotBlank()) {
            { Text(placeholder, color = VelaColors.TextTertiary) }
        } else null,
        supportingText = if (supportingText != null) {
            { Text(supportingText, color = VelaColors.TextTertiary, fontSize = 11.sp) }
        } else null,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = VelaColors.Accent,
            unfocusedBorderColor = VelaColors.StrokeEdge,
            focusedTextColor     = VelaColors.TextPrimary,
            unfocusedTextColor   = VelaColors.TextPrimary,
            cursorColor          = VelaColors.Accent,
        ),
        modifier = modifier,
    )
}

// ── Tool toggle row ────────────────────────────────────────────────────────────

@Composable
private fun ToolToggleRow(
    name:     String,
    enabled:  Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = name,
            style    = MonoMedium,
            color    = VelaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked         = enabled,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = VelaColors.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = VelaColors.SurfaceRaised,
            ),
        )
    }
}
