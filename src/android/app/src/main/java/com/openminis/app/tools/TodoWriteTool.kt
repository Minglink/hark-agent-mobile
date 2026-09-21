package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.TodoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * TodoWrite Tool — in-memory task management for the agent loop.
 *
 * Direct adaptation from open-claude-code (v2/src/tools/todo-write.mjs):
 * Maintains a structured task list that the agent uses to track multi-step work items.
 * The tasks are rendered interactively in the mobile chat UI for the user.
 */
object TodoWriteTool {
    const val NAME = "todo_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Manage an actionable task / todo list for multi-step tasks. " +
            "Pass an array of todos to create or update the task plan. " +
            "Supports statuses: 'pending', 'in_progress', 'completed', and priorities: 'high', 'medium', 'low'. " +
            "Always update this tool as you make progress on complex requests so the user can track what is done.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Plan multi-step refactoring tasks').",
            ),
            "todos" to AgentToolParam(
                "string",
                "JSON array of todo items (or array of objects): [{\"id\": \"1\", \"content\": \"task description\", \"status\": \"pending\"|\"in_progress\"|\"completed\", \"priority\": \"high\"|\"medium\"|\"low\"}].",
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
    )

    fun parseTodos(argsJson: String): List<TodoItem> {
        return try {
            val json = JSONObject(argsJson)
            val todosJson = when {
                json.has("todos") -> {
                    val raw = json.get("todos")
                    if (raw is JSONArray) raw
                    else if (raw is String) JSONArray(raw)
                    else JSONArray()
                }
                else -> JSONArray()
            }
            val result = mutableListOf<TodoItem>()
            var autoId = 1
            for (i in 0 until todosJson.length()) {
                val itemObj = todosJson.optJSONObject(i) ?: continue
                val content = itemObj.optString("content", "").trim()
                if (content.isEmpty()) continue
                val id = itemObj.optString("id", "").ifBlank { (autoId++).toString() }
                val status = when (itemObj.optString("status", "pending").lowercase()) {
                    "completed", "done" -> "completed"
                    "in_progress", "running", "active" -> "in_progress"
                    else -> "pending"
                }
                val priority = when (itemObj.optString("priority", "medium").lowercase()) {
                    "high" -> "high"
                    "low" -> "low"
                    else -> "medium"
                }
                result.add(
                    TodoItem(
                        id = id,
                        content = content,
                        status = status,
                        priority = priority,
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun execute(argsJson: String, onUpdate: (List<TodoItem>) -> Unit): ToolExecutionResult {
        val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = json.optString("tool_title", "Update Tasks")
        val items = parseTodos(argsJson)

        if (items.isEmpty()) {
            return ToolExecutionResult(
                output = "Error: 'todos' array is empty or malformed.",
                success = false,
                toolTitle = toolTitle,
            )
        }

        onUpdate(items)

        val summary = items.joinToString("\n") { t ->
            val icon = when (t.status) {
                "completed" -> "[x]"
                "in_progress" -> "[~]"
                else -> "[ ]"
            }
            "$icon ${t.id}. ${t.content} (${t.priority})"
        }

        return ToolExecutionResult(
            output = "Updated ${items.size} tasks:\n$summary",
            success = true,
            toolTitle = toolTitle,
        )
    }
}
