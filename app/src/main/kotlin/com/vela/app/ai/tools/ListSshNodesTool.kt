package com.vela.app.ai.tools

import com.vela.app.ssh.SshNodeRegistry

/** Lets the AI discover what Vela nodes are available before calling [SshCommandTool]. */
class ListSshNodesTool(private val nodeRegistry: SshNodeRegistry) : Tool {
    override val name        = "list_ssh_nodes"
    override val displayName = "List Nodes"
    override val icon        = "🔗"
    override val description = "Lists all Vela nodes (machines) this app can SSH into"
    override val parameters  = emptyList<ToolParameter>()

    override suspend fun execute(args: Map<String, Any>): String {
        val nodes = nodeRegistry.allSync()
        if (nodes.isEmpty()) return "No Vela nodes configured. Add one in the app's Nodes screen (🔗 icon)."
        return "Configured Vela nodes:\n" + nodes.joinToString("\n") { n ->
            "  • ${n.label}: ${n.username}@${n.host}:${n.port}"
        }
    }
}
