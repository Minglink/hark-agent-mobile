package com.openminis.app.tools.plan

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * AskUserQuestionTool — 移动端结构化向用户提问工具
 *
 * 借鉴 Open-ClaudeCode (src/tools/AskUserQuestionTool):
 * 在需求模糊或面临技术方案抉择时，向用户呈现可点选的单选/多选卡片，
 * 而不是在正文中输出冗长的自由提问，极大地优化移动端小屏交互效率。
 */
object AskUserQuestionTool {
    const val NAME = "ask_user_question"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Ask the user a structured multiple-choice question when requirements are ambiguous or " +
            "architectural choices must be made. The user can select from the provided options directly in the mobile UI.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise summary (e.g. 'Asking user to choose database solution')."),
            "question" to AgentToolParam("string", "The clear, direct question to ask."),
            "options" to AgentToolParam("string", "JSON array of strings or option objects representing selectable choices: [\"Option A\", \"Option B\"]"),
            "is_multi_select" to AgentToolParam("boolean", "Set to true if multiple options can be selected (default: false)."),
        ),
        required = listOf("tool_title", "question", "options"),
        propertyOrdering = listOf("tool_title", "question", "options", "is_multi_select"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        return try {
            val json = JSONObject(argsJson)
            val toolTitle = json.optString("tool_title", "Question for User")
            val question = json.optString("question", "")
            val isMulti = json.optBoolean("is_multi_select", false)

            val optionsList = mutableListOf<String>()
            val rawOptions = json.opt("options")
            if (rawOptions is JSONArray) {
                for (i in 0 until rawOptions.length()) {
                    val opt = rawOptions.opt(i)
                    if (opt is JSONObject) {
                        optionsList.add(opt.optString("label", opt.toString()))
                    } else if (opt != null) {
                        optionsList.add(opt.toString())
                    }
                }
            } else if (rawOptions is String) {
                optionsList.add(rawOptions)
            }

            val formattedOptions = optionsList.mapIndexed { idx, opt -> "${idx + 1}. $opt" }.joinToString("\n")
            ToolExecutionResult(
                output = "Rendered question in user interface:\nQuestion: $question\nOptions:\n$formattedOptions\n" +
                    "(Awaiting user selection or conversational input)",
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult("Failed to render user question: ${e.message}", false)
        }
    }
}
