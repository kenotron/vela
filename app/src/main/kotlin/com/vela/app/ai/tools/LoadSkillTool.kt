package com.vela.app.ai.tools

// TODO(Phase 2): Replace stub — wire to real SkillsEngine for skill directory loading
class LoadSkillTool : Tool {
    override val name = "load_skill"
    override val displayName = "Load Skill"
    override val icon = "🧠"
    override val description = "Load domain knowledge skills. Operations: list, search, info, skill_name. (Skills not yet configured — Phase 2)"
    override val parameters = listOf(
        ToolParameter("list", "boolean", "Return list of all available skills", required = false),
        ToolParameter("search", "string", "Search term to filter skills", required = false),
        ToolParameter("info", "string", "Get metadata for a skill without loading body", required = false),
        ToolParameter("skill_name", "string", "Name of skill to load", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val listAll = args["list"] as? Boolean ?: false
        val search = args["search"] as? String
        val info = args["info"] as? String
        val skillName = args["skill_name"] as? String

        return when {
            listAll || search != null -> "(no skills available — skills not yet configured)"
            info != null -> "Skill '$info' not found"
            skillName != null -> "Skill '$skillName' not found"
            else -> "Error: specify one of: list=true, search, info, or skill_name"
        }
    }
}
