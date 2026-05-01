package com.vela.app.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

@Composable
fun CoordinatorScreen(
    navController: NavController,
    viewModel: CoordinatorViewModel = hiltViewModel(),
) {
    val branches by viewModel.branches.collectAsState()

    // The entire background shifts to CoordBg — the teal temperature change
    // is the primary signal that you are in coordinator mode.
    Surface(
        color    = VelaColors.CoordBg,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CoordinatorAppBar(onBack = { navController.popBackStack() })
            CoordinatorStrip(
                nodeCount   = branches.size,
                currentStep = viewModel.currentStep,
                totalSteps  = viewModel.totalSteps,
            )
            LazyColumn(
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier            = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(branches) { index, branch ->
                    BranchCard(
                        branch        = branch,
                        onViewSession = { branchSessionId ->
                            // nodeId not available in coordinator context — pass empty string
                            navController.navigate(Routes.sessionDetail("", branchSessionId))
                        },
                    )
                    if (index < branches.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                        ConnectorLabel(label = "parallel · branch ${index + 2}")
                        Spacer(Modifier.height(6.dp))
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

// ── App bar ──────────────────────────────────────────────────────────────────

@Composable
private fun CoordinatorAppBar(onBack: () -> Unit) {
    // The gradient on the title text is the ONE permitted gradient in the app
    // (DESIGN.md §8 Screen 5). It runs from Accent (#5EEAD4) to AccentCoord (#1FE0C2).
    val titleGradient = Brush.linearGradient(
        colors = listOf(VelaColors.Accent, VelaColors.AccentCoord),
    )
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint               = VelaColors.AccentCoord,
            )
        }
        Text(
            text     = "Coordinator",
            style    = MaterialTheme.typography.displayMedium.merge(
                TextStyle(brush = titleGradient)
            ),
            modifier = Modifier.weight(1f),
        )
        CoordinatorBadge()
    }
}

@Composable
private fun CoordinatorBadge() {
    Surface(
        color = VelaColors.AccentCoord.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(VelaColors.AccentCoord),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text       = "coordinator",
                color      = VelaColors.AccentCoord,
                fontSize   = 10.sp,
                fontWeight = FontWeight(600),
            )
        }
    }
}

// ── Coordinator strip ────────────────────────────────────────────────────────

@Composable
private fun CoordinatorStrip(nodeCount: Int, currentStep: Int, totalSteps: Int) {
    Surface(
        color    = VelaColors.AccentCoord.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text       = "◉  Orchestrating $nodeCount nodes · step $currentStep",
                color      = VelaColors.AccentCoord,
                fontSize   = 11.sp,
                fontWeight = FontWeight(600),
            )
            StepPips(current = currentStep, total = totalSteps)
        }
    }
}

@Composable
private fun StepPips(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { index ->
            val pipIndex = index + 1
            val pipColor = when {
                pipIndex < current  -> VelaColors.Done
                pipIndex == current -> VelaColors.Running
                else                -> VelaColors.SurfaceRaised
            }
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pipColor),
            )
        }
    }
}

// ── Connector label ──────────────────────────────────────────────────────────

@Composable
private fun ConnectorLabel(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        HorizontalDivider(
            color    = VelaColors.AccentCoord.copy(alpha = 0.18f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text     = label,
            color    = VelaColors.TextTertiary,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(
            color    = VelaColors.AccentCoord.copy(alpha = 0.18f),
            modifier = Modifier.weight(1f),
        )
    }
}
