package com.openminis.app.tools.plan

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.guard.MutatingToolGuard
import org.json.JSONObject

/**
 * ExitPlanModeTool — 提交规划并释放写操作权限
 *
 * 借鉴 Open-ClaudeCode (src/tools/ExitPlanModeTool):
 * 提交完整的实施步骤，退出规划模式，恢复写操作与执行权限。
 */
object ExitPlanModeTool {
    const val NAME = "exit_plan_mode"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Exit Plan Mode after completing exploration and designing the implementation plan. " +
            "Submits the proposed steps and releases file-modifying restrictions so implementation can begin.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise summary shown to user (e.g. 'Submitting implementation plan')."),
            "plan_summary" to AgentToolParam("string", "A clear summary of the implementation strategy and decisions made."),
            "proposed_steps" to AgentToolParam("string", "Numbered or bulleted list of concrete implementation steps."),
            "affected_files" to AgentToolParam("string", "Comma-separated list or JSON array of files that will be created or modified."),
        ),
        required = listOf("tool_title", "plan_summary", "proposed_steps"),
        propertyOrdering = listOf("tool_title", "plan_summary", "proposed_steps", "affected_files"),
    )

    fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        return try {
            val json = JSONObject(argsJson)
            val toolTitle = json.optString("tool_title", "Exited Plan Mode")
            val planSummary = json.optString("plan_summary", "")
            val proposedSteps = json.optString("proposed_steps", "")

            MutatingToolGuard.setPlanModeActive(sessionId, false)
            com.openminis.app.goal.GoalManager.applyProposedSteps(sessionId, planSummary, proposedSteps)

            ToolExecutionResult(
                output = "Plan submitted and Plan Mode exited successfully. Write permissions restored.\n" +
                    "Plan Summary: $planSummary\n" +
                    "Steps: $proposedSteps\n" +
                    "You may now proceed with implementing the steps using file_write, file_edit, and shell_execute.",
                success = true,
                toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult("Failed to exit plan mode: ${e.message}", false)
        }
    }
}
