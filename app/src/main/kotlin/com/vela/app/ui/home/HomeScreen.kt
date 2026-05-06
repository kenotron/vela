package com.vela.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

/**
 * Home screen — the fleet overview.
 *
 * Shows a vertical list of node tiles (one per SshNode in the database) or an
 * empty state when no nodes are connected yet. No bottom nav bar. Voice FAB is
 * provided by the parent VelaApp scaffold and is not touched here.
 *
 * Design spec: DESIGN.md §8 (Screen 1)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val nodes by viewModel.nodes.collectAsState()
    val nodeConnectivity by viewModel.nodeConnectivity.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // "Vela" wordmark — Inter 700, accent color, 18sp (DESIGN.md §8)
                    Text(
                        text       = "Vela",
                        color      = VelaColors.Accent,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    // "Connect a node" shortcut — shown when at least one node exists so
                    // the user always has a path to add more nodes (not just the empty state).
                    if (nodes.isNotEmpty()) {
                        IconButton(onClick = { navController.navigate(Routes.CONNECT_NODE) }) {
                            Icon(
                                imageVector        = Icons.Default.Add,
                                contentDescription = "Connect a node",
                                tint               = VelaColors.Accent,
                            )
                        }
                    }
                    // Settings gear — navigates to API key settings screen
                    IconButton(onClick = { navController.navigate(Routes.API_KEYS) }) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint               = VelaColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
        containerColor = VelaColors.Abyss,
    ) { paddingValues ->

        if (nodes.isEmpty()) {
            EmptyState(
                onConnectClick = { navController.navigate(Routes.CONNECT_NODE) },
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = 24.dp, // standard bottom padding
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(nodes, key = { it.id }) { node ->
                    NodeTileItem(
                        node         = node,
                        connectivity = nodeConnectivity[node.id],
                        onClick      = { navController.navigate(Routes.nodeDetail(node.id)) },
                    )
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

/**
 * Shown when there are no SshNodes in the database.
 *
 * Spec: centered serif text + cyan "Connect a node" button.
 * No illustrations. No gimmicks. (DESIGN.md §9.11)
 */
@Composable
private fun EmptyState(
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text      = "No nodes connected yet",
                style     = MaterialTheme.typography.displaySmall,
                color     = VelaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onConnectClick,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = VelaColors.Accent,
                    contentColor   = VelaColors.Abyss,
                ),
                shape    = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    text  = "Connect a node",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
