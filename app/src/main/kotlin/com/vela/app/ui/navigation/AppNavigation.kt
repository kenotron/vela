package com.vela.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.theme.VelaColors
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vela.app.ui.approval.ApprovalGateSheet
import com.vela.app.ui.approval.ApprovalSheetViewModel
import com.vela.app.ui.connectnode.ConnectNodeScreen
import com.vela.app.ui.home.HomeScreen
import com.vela.app.ui.nodeconfig.NodeConfigScreen
import com.vela.app.ui.nodedetail.NodeDetailScreen
import com.vela.app.ui.sessiondetail.SessionDetailScreen
import com.vela.app.ui.sessionlist.SessionListScreen
import com.vela.app.ui.settings.ApiKeySettingsScreen

// ── Routes ────────────────────────────────────────────────────────────────────

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
    const val SESSION_LIST = "node/{nodeId}/project/{projectId}?projectName={projectName}&workingDir={workingDir}"
    // SESSION_DETAIL includes nodeId so SessionDetailViewModel can build the API client
    const val SESSION_DETAIL = "session/{nodeId}/{sessionId}"
    const val COORDINATOR  = "session/{sessionId}/coordinator"
    const val NODE_CONFIG  = "node/{nodeId}/config"
    const val CONNECT_NODE = "connect"
    const val API_KEYS     = "api-keys"

    fun nodeDetail(nodeId: String)                                     = "node/$nodeId"
    fun sessionList(nodeId: String, projectId: String, projectName: String = "", workingDir: String = "~") =
        "node/$nodeId/project/$projectId?projectName=${android.net.Uri.encode(projectName)}&workingDir=${android.net.Uri.encode(workingDir)}"
    fun sessionDetail(nodeId: String, sessionId: String)              = "session/$nodeId/$sessionId"
    fun coordinator(sessionId: String)                                 = "session/$sessionId/coordinator"
    fun nodeConfig(nodeId: String)                                     = "node/$nodeId/config"
}

// ── Root composable ────────────────────────────────────────────────────────────

/**
 * Root composable for the entire Vela UI.
 *
 * Contains the [NavHost] for hierarchical navigation (Home → Node → Project →
 * Session). Voice input now lives inside SessionDetailScreen only — no global FAB.
 * The [ApprovalGateSheet] is driven by [ApprovalSheetViewModel].
 */
@Composable
fun VelaApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val approvalVm: ApprovalSheetViewModel = hiltViewModel()
    val approvalReq by approvalVm.request.collectAsState()

    Scaffold(
        modifier       = modifier,
        containerColor = VelaColors.Abyss,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            NavHost(
                navController    = navController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) { HomeScreen(navController) }

                composable(Routes.NODE_DETAIL) { NodeDetailScreen(navController) }

                composable(
                    route     = Routes.SESSION_LIST,
                    arguments = listOf(
                        navArgument("nodeId")      { type = NavType.StringType },
                        navArgument("projectId")   { type = NavType.StringType },
                        navArgument("projectName") { type = NavType.StringType; defaultValue = "" },
                        navArgument("workingDir")  { type = NavType.StringType; defaultValue = "~" },
                    ),
                ) { SessionListScreen(navController) }

                composable(
                    route     = Routes.SESSION_DETAIL,
                    arguments = listOf(
                        navArgument("nodeId")    { type = NavType.StringType },
                        navArgument("sessionId") { type = NavType.StringType },
                    ),
                ) { SessionDetailScreen(navController) }

                composable(Routes.COORDINATOR) {
                    com.vela.app.ui.coordinator.CoordinatorScreen(navController)
                }

                composable(
                    route     = Routes.NODE_CONFIG,
                    arguments = listOf(
                        navArgument("nodeId") { type = NavType.StringType }
                    ),
                ) { backStackEntry ->
                    val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
                    NodeConfigScreen(
                        nodeId         = nodeId,
                        onNavigateBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.CONNECT_NODE) {
                    ConnectNodeScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onConnected    = { navController.popBackStack() },
                    )
                }

                composable(Routes.API_KEYS) {
                    ApiKeySettingsScreen(onNavigateBack = { navController.popBackStack() })
                }
            }

            approvalReq?.let { req ->
                ApprovalGateSheet(
                    request   = req,
                    onApprove = { approvalVm.approve() },
                    onDeny    = { approvalVm.deny() },
                )
            }
        } // Box
    } // Scaffold
}
