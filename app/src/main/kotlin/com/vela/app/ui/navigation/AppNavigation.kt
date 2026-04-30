package com.vela.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vela.app.ui.home.HomeScreen
import com.vela.app.ui.nodedetail.NodeDetailScreen
import com.vela.app.ui.theme.VelaColors

// ── Routes

/**
 * All navigation routes in the Vela NavHost graph.
 *
 * Pattern strings (with `{param}` placeholders) are used when registering
 * composable destinations. Helper functions build concrete route strings for
 * [NavController.navigate] calls.
 */
object Routes {
    const val HOME         = "home"
    const val NODE_DETAIL  = "node/{nodeId}"
    const val SESSION_LIST = "node/{nodeId}/project/{projectId}"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val COORDINATOR  = "session/{sessionId}/coordinator"
    const val NODE_CONFIG  = "node/{nodeId}/config"
    const val CONNECT_NODE = "connect"

    fun nodeDetail(nodeId: String)                         = "node/$nodeId"
    fun sessionList(nodeId: String, projectId: String)     = "node/$nodeId/project/$projectId"
    fun sessionDetail(sessionId: String)                   = "session/$sessionId"
    fun coordinator(sessionId: String)                     = "session/$sessionId/coordinator"
    fun nodeConfig(nodeId: String)                         = "node/$nodeId/config"
}

// ── Root composable

/**
 * Root composable for the entire Vela UI.
 *
 * Contains the [NavHost] for hierarchical navigation (Home → Node → Project →
 * Session) and the persistent [VoiceFabPlaceholder] overlaid at bottom-right
 * above all screens.
 *
 * All screen destinations are placeholders in Phase 1. They are replaced
 * screen-by-screen in Phases 2–6.
 */
@Composable
fun VelaApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController    = navController,
            startDestination = Routes.HOME,
        ) {
            composable(Routes.HOME)           { HomeScreen(navController) }
            composable(Routes.NODE_DETAIL)    { NodeDetailScreen(navController) }
            composable(Routes.SESSION_LIST)   { SessionListPlaceholder(navController) }
            composable(Routes.SESSION_DETAIL) { SessionDetailPlaceholder(navController) }
            composable(Routes.COORDINATOR)    { CoordinatorPlaceholder(navController) }
            composable(Routes.NODE_CONFIG)    { NodeConfigPlaceholder(navController) }
            composable(Routes.CONNECT_NODE)   { ConnectNodePlaceholder(navController) }
        }

        // Persistent Voice FAB — always on top, always bottom-right.
        // Replaced with the real VoiceFab in Phase 2.
        VoiceFabPlaceholder(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }
}

// ── Placeholder screens
// Each is a minimal Surface + Text so the NavHost graph compiles and the app
// launches. Replaced screen-by-screen in Phases 2–6.

@Composable
private fun SessionListPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session List", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun SessionDetailPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Session Detail", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun CoordinatorPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.CoordBg) {
        Text(text = "Coordinator", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun NodeConfigPlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Node Config", color = VelaColors.TextPrimary)
    }
}

@Composable
private fun ConnectNodePlaceholder(navController: NavController) {
    Surface(modifier = Modifier.fillMaxSize(), color = VelaColors.Abyss) {
        Text(text = "Connect a Node", color = VelaColors.TextPrimary)
    }
}

// ── Voice FAB placeholder

/**
 * Placeholder for the persistent Voice FAB.
 *
 * Matches the DESIGN.md §7.7 idle-state dimensions (64dp, cyan ring on
 * SurfacePeak disc) without any animation or interaction logic.
 * The real VoiceFab with bloom animation is built in Phase 2.
 */
@Composable
fun VoiceFabPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(VelaColors.SurfacePeak)
            .border(1.5.dp, VelaColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Default.Mic,
            contentDescription = "Voice",
            tint               = VelaColors.Accent,
            modifier           = Modifier.size(26.dp),
        )
    }
}
