package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool definitions and execution for memory_write and memory_get.
 * Keeps legacy daily/all calls while routing default project calls in isolation.
 */
object MemoryTools {
    const val WRITE_DESCRIPTION = "Save a timestamped memory entry. By default, writes to the active project's .hark/memory/PROJECT.md; outside a project, writes to today's daily log (YYYY-MM-DD.md). Save concise facts, preferences and conventions. Use scope=daily only when explicitly saving a cross-session daily note. GLOBAL.md preferences are updated with file tools only when the user explicitly requests it. Never store credentials or secrets without explicit informed confirmation."
    const val WRITE_SCOPE_DESCRIPTION = "auto (default): current project, otherwise daily; project: current project's PROJECT.md (requires an active project); daily: cross-session daily log."
    const val READ_DESCRIPTION = "Search persistent memory using keywords and surrounding context. By default, searches the active project's complete PROJECT.md plus user-maintained GLOBAL.md preferences, excluding other sessions' daily logs. Outside a project, defaults to GLOBAL.md plus daily logs."
    const val READ_SCOPE_DESCRIPTION = "auto (default): current PROJECT.md + GLOBAL.md, or GLOBAL.md + daily logs outside a project; project: PROJECT.md only; global: GLOBAL.md preferences only; daily: daily logs only; all: legacy GLOBAL.md + daily logs (does not include project memory)."

    // -- Tool Definitions (Anthropic format) --

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user.")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", WRITE_SCOPE_DESCRIPTION)
                put("enum", JSONArray(listOf("auto", "project", "daily")))
            })
        }

        return JSONObject().apply {
            put("name", "memory_write")
            put("description", WRITE_DESCRIPTION)
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                    put("content")
                })
            })
        }
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", READ_SCOPE_DESCRIPTION)
                put("enum", JSONArray(listOf("auto", "project", "global", "daily", "all")))
            })
            put("keywords", JSONObject().apply {
                put("type", "string")
                put("description", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_get")
            put("description", READ_DESCRIPTION)
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                })
            })
        }
    }

    // -- OpenAI Function Calling format --

    fun memoryWriteOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_write")
                put("description", memoryWriteToolDefinition().getString("description"))
                put("parameters", memoryWriteToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    fun memoryGetOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_get")
                put("description", memoryGetToolDefinition().getString("description"))
                put("parameters", memoryGetToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
        /** Actual write destination for UI edit/revoke; null for read/errors. */
        val scope: String? = null,
        val projectWorkspacePath: String? = null,
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository, workspaceDir: File? = null): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")
            val requestedScope = obj.optString("scope", "auto").trim().lowercase().ifEmpty { "auto" }
            if (requestedScope !in setOf("auto", "project", "daily")) {
                return ToolResult("Error: Invalid write scope '$requestedScope'. Use auto, project, or daily. GLOBAL.md is updated using file tools only on explicit user request.", false, toolTitle)
            }
            // Older project agents used target=global to opt into daily logs.
            val scope = if (requestedScope != "auto") requestedScope else when {
                obj.optString("target", "").equals("global", ignoreCase = true) -> "daily"
                workspaceDir != null -> "project"
                else -> "daily"
            }

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else if (scope == "project" && workspaceDir == null) {
                ToolResult("Error: Project memory requires an active project workspace", false, toolTitle)
            } else {
                val projectPath = if (scope == "project") workspaceDir!!.canonicalPath else null
                val result = if (scope == "project") repository.writeProjectMemory(workspaceDir!!, content) else repository.writeMemory(content)
                val success = !result.startsWith("Error")
                ToolResult(result, success, toolTitle, if (success) scope else null, if (success) projectPath else null)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository, workspaceDir: File? = null): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "auto")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = repository.getMemory(keywords, scope, workspaceDir)
            ToolResult(result, !result.startsWith("Error:"), toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
