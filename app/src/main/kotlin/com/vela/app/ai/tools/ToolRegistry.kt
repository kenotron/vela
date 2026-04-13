    package com.vela.app.ai.tools

    import javax.inject.Singleton

    @Singleton
    class ToolRegistry(private val tools: List<Tool>) {
        private val byName: Map<String, Tool> = tools.associateBy { it.name }

        fun all(): List<Tool> = tools
        fun contains(name: String): Boolean = name in byName
        fun find(name: String): Tool? = byName[name]

        suspend fun execute(name: String, args: Map<String, Any>): String {
            val tool = byName[name] ?: return "Unknown tool: $name"
            return tool.execute(args)
        }
    }
    