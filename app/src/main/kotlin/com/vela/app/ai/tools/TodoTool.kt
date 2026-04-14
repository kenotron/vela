package com.vela.app.ai.tools

import org.json.JSONArray

class TodoTool : Tool {
    override val name = "todo"
    override val displayName = "Todo List"
    override val icon = "✅"
    override val description = "Manage a session todo list. action: 'create' (replace all), 'update' (replace all), 'list' (read current)."
    override val parameters = listOf(
        ToolParameter("action", "string", "Action: 'create', 'update', or 'list'"),
        ToolParameter("todos", "string",
            "JSON array of todo objects with 'content', 'status' (pending/in_progress/completed), 'activeForm'",
            required = false),
    )

    private data class TodoItem(val content: String, val status: String, val activeForm: String)
    private var todos: List<TodoItem> = emptyList()

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return "Error: action is required"
        return when (action) {
            "create", "update" -> {
                val todosJson = args["todos"] as? String
                    ?: return "Error: todos is required for '$action'"
                todos = parseTodos(todosJson)
                    ?: return "Error: todos must be a valid JSON array"
                val counts = todos.groupBy { it.status }.mapValues { it.value.size }
                "Todo list updated: ${todos.size} items (${counts["pending"] ?: 0} pending, " +
                "${counts["in_progress"] ?: 0} in progress, ${counts["completed"] ?: 0} completed)"
            }
            "list" -> {
                if (todos.isEmpty()) return "(no todos)"
                todos.joinToString("\n") { item ->
                    val icon = when (item.status) {
                        "completed" -> "✓"
                        "in_progress" -> "→"
                        else -> "○"
                    }
                    "$icon ${item.content}"
                }
            }
            else -> "Error: unknown action '$action'. Use 'create', 'update', or 'list'"
        }
    }

    private fun parseTodos(json: String): List<TodoItem>? = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TodoItem(
                content = obj.getString("content"),
                status = obj.optString("status", "pending"),
                activeForm = obj.optString("activeForm", ""),
            )
        }
    }.getOrNull()
}
