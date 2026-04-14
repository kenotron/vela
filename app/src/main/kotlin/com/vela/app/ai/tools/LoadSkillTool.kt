package com.vela.app.ai.tools

import com.vela.app.skills.SkillLoadResult
import com.vela.app.skills.SkillsEngine

class LoadSkillTool(private val engine: SkillsEngine) : Tool {
    override val name        = "load_skill"
    override val displayName = "Load Skill"
    override val icon        = "🧠"
    override val description =
        "Load domain knowledge from a skill. Operations: list (all available skills), " +
        "search (filter by keyword), info (metadata only), skill_name (load full content)."
    override val parameters = listOf(
        ToolParameter("list",       "boolean", "If true, return list of all available skills",                required = false),
        ToolParameter("search",     "string",  "Search term to filter skills by name or description",        required = false),
        ToolParameter("info",       "string",  "Get metadata for a skill without loading body",              required = false),
        ToolParameter("skill_name", "string",  "Name of skill to load full content",                        required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val listAll   = args["list"]       as? Boolean ?: false
        val search    = args["search"]     as? String
        val info      = args["info"]       as? String
        val skillName = args["skill_name"] as? String

        return when {
            listAll -> {
                val skills = engine.discoverAll()
                if (skills.isEmpty()) "(no skills available)"
                else skills.joinToString("\n") { "- **${it.name}**: ${it.description}" }
            }
            search != null -> {
                val skills = engine.search(search)
                if (skills.isEmpty()) "No skills matching: $search"
                else skills.joinToString("\n") { "- **${it.name}**: ${it.description}" }
            }
            info != null -> {
                engine.info(info)?.let {
                    "**${it.name}** — ${it.description}\n" +
                    "fork=${it.isFork}  user-invocable=${it.isUserInvocable}\n" +
                    "directory: ${it.directory}"
                } ?: "Skill '$info' not found"
            }
            skillName != null -> when (val r = engine.load(skillName)) {
                is SkillLoadResult.Content    -> "${r.body}\n\nskill_directory: ${r.skillDirectory}"
                is SkillLoadResult.NotFound   -> "Skill '$skillName' not found"
                is SkillLoadResult.ForkResult -> r.response
                is SkillLoadResult.Error      -> "Error: ${r.message}"
            }
            else -> "Error: specify list=true, search=\"<query>\", info=\"<name>\", or skill_name=\"<name>\""
        }
    }
}
