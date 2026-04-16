package com.vela.app.ai.tools

    import com.vela.app.ssh.NodeType
    import com.vela.app.ssh.SshNodeRegistry

    /**
     * Gives the model a directory of all configured nodes — both SSH servers
     * and amplifierd daemons — with enough detail to pass the right label to
     * [RunInNodeTool].
     */
    class ListNodesTool(private val registry: SshNodeRegistry) : Tool {
        override val name        = "list_nodes"
        override val displayName = "List Nodes"
        override val icon        = "🔗"
        override val description = "Lists all connected Vela nodes — SSH servers and Amplifier daemons. Call this first to discover node labels before using run_in_node."
        override val parameters  = emptyList<ToolParameter>()

        override suspend fun execute(args: Map<String, Any>): String {
            val nodes = registry.allSync()
            if (nodes.isEmpty()) return "No nodes configured. Add one in Settings → Connections."
            return buildString {
                appendLine("Configured nodes (${nodes.size}):")
                nodes.forEach { n ->
                    when (n.type) {
                        NodeType.SSH        -> appendLine("  • [SSH]        ${n.label} — ${n.username}@${n.primaryHost}:${n.port}")
                        NodeType.AMPLIFIERD -> appendLine("  • [amplifierd] ${n.label} — ${n.url}")
                    }
                }
                append("Use run_in_node with the label shown above.")
            }
        }
    }
    