package com.openminis.app.tools.guard

import java.util.concurrent.ConcurrentHashMap

/**
 * MutatingToolGuard — 规划期写操作硬熔断守护
 *
 * 借鉴 Open-ClaudeCode:
 * 在 Plan Mode（规划模式）激活期间，禁止调用修改文件或破坏性 Shell 命令，
 * 迫使 Agent 在进行实际变更前彻底调研并完成两阶段规划审批。
 */
object MutatingToolGuard {

    private val sessionPlanModeMap = ConcurrentHashMap<String, Boolean>()

    fun isPlanModeActive(sessionId: String): Boolean =
        sessionPlanModeMap[sessionId] == true

    fun setPlanModeActive(sessionId: String, active: Boolean) {
        if (active) {
            sessionPlanModeMap[sessionId] = true
        } else {
            sessionPlanModeMap.remove(sessionId)
        }
    }

    sealed class GuardResult {
        object Allowed : GuardResult()
        data class Blocked(val reason: String) : GuardResult()
    }

    /**
     * 检查工具或命令是否在当前状态下被允许执行
     */
    fun checkToolExecution(sessionId: String, toolName: String, command: String? = null): GuardResult {
        if (!isPlanModeActive(sessionId)) {
            return GuardResult.Allowed
        }

        // 在规划模式下，硬拦截直接文件修改工具
        if (toolName in listOf("file_write", "file_edit")) {
            return GuardResult.Blocked(
                "Execution blocked by Plan Mode: You are currently in PLAN MODE. Direct file modifications " +
                "($toolName) are disabled. Please explore the codebase, design your changes, and call " +
                "`exit_plan_mode` with your final plan before modifying files."
            )
        }

        // 拦截破坏性 Shell 命令
        if (toolName == "shell_execute" && !command.isNullOrBlank()) {
            val lower = command.lowercase().trim()
            val destructiveKeywords = listOf("rm ", "rmdir", "mv ", "> ", ">> ", "git commit", "git push", "sed -i")
            if (destructiveKeywords.any { lower.contains(it) }) {
                return GuardResult.Blocked(
                    "Execution blocked by Plan Mode: Destructive or file-modifying command detected in shell_execute. " +
                    "Use read-only commands (ls, cat, grep, find, git status/diff) during plan mode, or call `exit_plan_mode` first."
                )
            }
        }

        return GuardResult.Allowed
    }
}
