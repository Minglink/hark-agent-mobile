package com.openminis.app.tools.plan

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.guard.MutatingToolGuard
import org.json.JSONObject

/**
 * EnterPlanModeTool — 进入两阶段安全规划模式
 *
 * 借鉴 Open-ClaudeCode (src/tools/EnterPlanModeTool):
 * 在面对非轻量（Non-trivial）或多文件修改任务时，主动进入只读规划模式。
 */
object EnterPlanModeTool {
    const val NAME = "enter_plan_mode"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Transition into Plan Mode for non-trivial tasks. " +
            "In plan mode, file-modifying tools (file_write, file_edit) and destructive shell commands are disabled. " +
            "You can thoroughly explore the codebase using file_read and read-only shell commands, understand patterns, " +
            "and formulate a step-by-step plan. Call exit_plan_mode when your plan is ready for user review.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise summary shown to user (e.g. 'Entering Plan Mode to investigate task')."),
            "goal" to AgentToolParam("string", "The objective or scope of what is being planned."),
        ),
        required = listOf("tool_title", "goal"),
        propertyOrdering = listOf("tool_title", "goal"),
    )

    fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        return try {
            val json = JSONObject(argsJson)
            val toolTitle = json.optString("tool_title", "Entering Plan Mode")
            val goal = json.optString("goal", "")

            MutatingToolGuard.setPlanModeActive(sessionId, true)

            ToolExecutionResult(
                output = "Entered Plan Mode successfully for goal: '$goal'. " +
                    "File modification tools are now gated. Explore the codebase with file_read, design your steps, " +
                    "and invoke `exit_plan_mode` with your final plan when ready.",
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult("Failed to enter plan mode: ${e.message}", false)
        }
    }
}
