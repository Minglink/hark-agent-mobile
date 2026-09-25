package com.openminis.app.core

import com.openminis.app.agent.team.model.TeamTaskBoard
import com.openminis.app.agent.team.model.TeamTaskStatus
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.wire.ReasoningEffortNormalizer
import com.openminis.app.provider.wire.ToolWireNormalizer
import com.openminis.app.security.SecretRedactor
import com.openminis.app.tools.guard.MutatingToolGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V122CoreTransformationsTest
 *
 * 验证 1.2.2 版本新增的核心创新与架构改造：
 * 1. 跨厂商推理预算归一化 (ReasoningEffortNormalizer)
 * 2. 工具调用与输出协议垫片 (ToolWireNormalizer)
 * 3. 在途敏感信息脱敏与内容部件保护 (SecretRedactor)
 * 4. 两阶段规划模式写操作熔断守护 (MutatingToolGuard)
 * 5. 多 Agent 任务 DAG 与依赖推进 (TeamTaskDag)
 */
class V122CoreTransformationsTest {

    @Test
    fun testReasoningEffortNormalizer() {
        // 1. Anthropic Extended Thinking
        assertNull(ReasoningEffortNormalizer.resolveAnthropicBudgetTokens(ThinkingLevel.OFF))
        assertEquals(1024, ReasoningEffortNormalizer.resolveAnthropicBudgetTokens(ThinkingLevel.LOW))
        assertEquals(4096, ReasoningEffortNormalizer.resolveAnthropicBudgetTokens(ThinkingLevel.MEDIUM))
        assertEquals(8192, ReasoningEffortNormalizer.resolveAnthropicBudgetTokens(ThinkingLevel.HIGH))
        // 确保被 maxOutputTokens - 1024 封顶
        assertEquals(3072, ReasoningEffortNormalizer.resolveAnthropicBudgetTokens(ThinkingLevel.HIGH, maxOutputTokens = 4096))

        // 2. OpenAI Reasoning Effort
        assertNull(ReasoningEffortNormalizer.resolveOpenAIReasoningEffort(ThinkingLevel.OFF))
        assertEquals("low", ReasoningEffortNormalizer.resolveOpenAIReasoningEffort(ThinkingLevel.LOW))
        assertEquals("medium", ReasoningEffortNormalizer.resolveOpenAIReasoningEffort(ThinkingLevel.MEDIUM))
        assertEquals("high", ReasoningEffortNormalizer.resolveOpenAIReasoningEffort(ThinkingLevel.HIGH))
        assertEquals("high", ReasoningEffortNormalizer.resolveOpenAIReasoningEffort(ThinkingLevel.MAX))

        // 3. Gemini Thinking Budget
        assertEquals(0, ReasoningEffortNormalizer.resolveGeminiThinkingBudget(ThinkingLevel.OFF))
        assertEquals(1024, ReasoningEffortNormalizer.resolveGeminiThinkingBudget(ThinkingLevel.LOW))
        assertEquals(4096, ReasoningEffortNormalizer.resolveGeminiThinkingBudget(ThinkingLevel.MEDIUM))
        assertEquals(8192, ReasoningEffortNormalizer.resolveGeminiThinkingBudget(ThinkingLevel.HIGH))
        assertEquals(16384, ReasoningEffortNormalizer.resolveGeminiThinkingBudget(ThinkingLevel.ULTRA))

        // 4. DeepSeek Floor
        assertEquals(2048, ReasoningEffortNormalizer.resolveDeepSeekTokenFloor(ThinkingLevel.OFF, 2048))
        assertEquals(4096, ReasoningEffortNormalizer.resolveDeepSeekTokenFloor(ThinkingLevel.LOW, 2048))
        assertEquals(16384, ReasoningEffortNormalizer.resolveDeepSeekTokenFloor(ThinkingLevel.HIGH, 4096))
    }

    @Test
    fun testToolWireNormalizer() {
        // 空输出垫入 "(empty output)"
        assertEquals("(empty output)", ToolWireNormalizer.normalizeToolResultContent(null))
        assertEquals("(empty output)", ToolWireNormalizer.normalizeToolResultContent(""))
        assertEquals("(empty output)", ToolWireNormalizer.normalizeToolResultContent("   \n\t  "))

        // 正常输出保持原样
        assertEquals("Normal result", ToolWireNormalizer.normalizeToolResultContent("Normal result"))

        // 超大输出截断
        val massive = "A".repeat(130_000)
        val truncated = ToolWireNormalizer.normalizeToolResultContent(massive)
        assertTrue(truncated.contains("[Hark Wire Guard: Omitted"))
        assertTrue(truncated.length < 130_000)

        // Tool Call ID 过滤
        assertEquals("call_a_b_safe", ToolWireNormalizer.normalizeToolCallId("a:b"))
        assertEquals("call_123_456", ToolWireNormalizer.normalizeToolCallId("call_123_456"))
    }

    @Test
    fun testSecretRedactor() {
        val rawSecret = "My API key is sk-1234567890abcdef1234567890 and anthropic sk-ant-api03-abcdefgh12345678901234567890"
        val redacted = SecretRedactor.redact(rawSecret)
        assertNotNull(redacted)
        assertFalse(redacted!!.contains("1234567890abcdef1234567890"))
        assertTrue(redacted.contains("sk-[REDACTED_SECRET]"))
        assertTrue(redacted.contains("sk-ant-[REDACTED_SECRET]"))

        // GitHub Token
        val githubToken = "Token is ghp_123456789012345678901234567890123456"
        val redactedGh = SecretRedactor.redact(githubToken)
        assertEquals("Token is ghp_[REDACTED_SECRET]", redactedGh)

        // 验证消息列表与嵌套 AgentContentPart 脱敏
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "Execute cat token.txt",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "call_1",
                        name = "cat",
                        content = "PRIVATE_KEY=AKIAIOSFODNN7EXAMPLE"
                    )
                )
            )
        )
        val safeMessages = SecretRedactor.redactMessages(messages)
        val safeToolResult = safeMessages[0].contentParts[0] as AgentContentPart.ToolResult
        assertFalse(safeToolResult.content.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(safeToolResult.content.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun testMutatingToolGuard() {
        val testSession = "test-session-guard"

        // 默认状态：允许操作
        val allow1 = MutatingToolGuard.checkToolExecution(testSession, "file_write")
        assertTrue(allow1 is MutatingToolGuard.GuardResult.Allowed)

        // 激活规划模式
        MutatingToolGuard.setPlanModeActive(testSession, true)
        assertTrue(MutatingToolGuard.isPlanModeActive(testSession))

        // 拦截文件写工具
        val blockedWrite = MutatingToolGuard.checkToolExecution(testSession, "file_write")
        assertTrue(blockedWrite is MutatingToolGuard.GuardResult.Blocked)

        val blockedEdit = MutatingToolGuard.checkToolExecution(testSession, "file_edit")
        assertTrue(blockedEdit is MutatingToolGuard.GuardResult.Blocked)

        // 拦截破坏性 Shell 命令
        val blockedRm = MutatingToolGuard.checkToolExecution(testSession, "shell_execute", "rm -rf /data")
        assertTrue(blockedRm is MutatingToolGuard.GuardResult.Blocked)

        val blockedCommit = MutatingToolGuard.checkToolExecution(testSession, "shell_execute", "git commit -m 'wip'")
        assertTrue(blockedCommit is MutatingToolGuard.GuardResult.Blocked)

        // 允许只读 Shell 命令
        val allowLs = MutatingToolGuard.checkToolExecution(testSession, "shell_execute", "ls -la")
        assertTrue(allowLs is MutatingToolGuard.GuardResult.Allowed)

        val allowGitDiff = MutatingToolGuard.checkToolExecution(testSession, "shell_execute", "git diff HEAD")
        assertTrue(allowGitDiff is MutatingToolGuard.GuardResult.Allowed)

        // 退出规划模式
        MutatingToolGuard.setPlanModeActive(testSession, false)
        val allowAfterExit = MutatingToolGuard.checkToolExecution(testSession, "file_write")
        assertTrue(allowAfterExit is MutatingToolGuard.GuardResult.Allowed)
    }

    @Test
    fun testTeamTaskDag() {
        val board = TeamTaskBoard()

        // 插入任务 1 (无依赖)
        val task1 = board.upsertTask(
            id = "task-1",
            subject = "Inspect codebase",
            description = "Explore directory structure"
        )
        assertEquals(TeamTaskStatus.PENDING, task1.status)

        // 插入任务 2 (依赖 task-1)
        val task2 = board.upsertTask(
            id = "task-2",
            subject = "Implement changes",
            description = "Apply refactoring",
            blockedBy = listOf("task-1")
        )
        assertEquals(TeamTaskStatus.WAITING_BLOCKER, task2.status)

        // 查看就绪任务：此时只有 task-1
        var ready = board.getReadyTasks()
        assertEquals(1, ready.size)
        assertEquals("task-1", ready[0].id)

        // 将 task-1 标记为完成
        board.updateStatus("task-1", TeamTaskStatus.COMPLETED)

        // task-2 应该自动解除阻塞并变为就绪
        ready = board.getReadyTasks()
        val readyIds = ready.map { it.id }
        assertTrue(readyIds.contains("task-2"))
        val updatedTask2 = board.getTask("task-2")
        assertEquals(TeamTaskStatus.PENDING, updatedTask2?.status)
    }
}
