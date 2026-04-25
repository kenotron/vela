package com.vela.app.ai

import org.json.JSONArray

/**
 * UI-side mirror of [`amplifier_module_agent_runtime::AgentConfig`].
 *
 * Populated by parsing the JSON returned by [`AmplifierBridge.nativeListAgents`].
 * [tools] is empty when the agent inherits all available tools.
 */
data class AgentRef(
    val name: String,
    val description: String,
    val tools: List<String>,
) {
    companion object {
        /**
         * Parse the JSON array returned by `nativeListAgents`. Returns an
         * empty list for any malformed input — the caller treats "no agents"
         * and "parse failed" the same way.
         */
        fun parseJsonArray(json: String): List<AgentRef> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AgentRef(
                    name        = obj.getString("name"),
                    description = obj.optString("description", ""),
                    tools       = obj.optJSONArray("tools")
                        ?.let { ts -> (0 until ts.length()).map { ts.getString(it) } }
                        ?: emptyList(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
