package com.openminis.app.agent.subagent

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 混合专家 (Mixture of Agents) 并发编排引擎
 * 核心算法严格借鉴 hermes-agent moa_loop.py
 */
object MoAEngine {

    private const val TAG = "MoAEngine"
    private const val REFERENCE_TOOL_RESULT_BUDGET = 2000

    private const val ADVISORY_INSTRUCTION =
        "[The conversation above is the current state of the task. Give your " +
        "most intelligent judgement: what is going on, what should happen next, " +
        "what risks or mistakes you see, and how the acting agent should " +
        "proceed.]"

    /**
     * 将主会话完整历史转换为顾问专用的轻量 Advisory View
     * 对应 hermes _reference_messages()
     */
    fun buildAdvisoryMessages(history: List<LLMMessage>): List<LLMMessage> {
        val rendered = mutableListOf<LLMMessage>()
        var lastUserText: String? = null

        for (msg in history) {
            when (msg.role) {
                LLMMessage.Role.USER -> {
                    val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                    if (toolResults.isNotEmpty()) {
                        for (tr in toolResults) {
                            val preview = truncateToolResult(tr.content, REFERENCE_TOOL_RESULT_BUDGET)
                            val block = "[工具执行结果 (${tr.name}): $preview]"
                            val last = rendered.lastOrNull()
                            if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
                                rendered[rendered.lastIndex] = last.copy(
                                    content = last.content + "\n" + block
                                )
                            } else {
                                rendered.add(
                                    LLMMessage(
                                        role = LLMMessage.Role.ASSISTANT,
                                        content = block,
                                    )
                                )
                            }
                        }
                    } else {
                        val text = msg.content.trim().ifEmpty {
                            msg.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }.trim()
                        }
                        val finalText = if (text.isEmpty() && (msg.imageParts.isNotEmpty() || msg.contentParts.any { it is AgentContentPart.ImageData })) {
                            "[用户发送了图片或多模态附件]"
                        } else {
                            text
                        }
                        if (finalText.isNotEmpty()) {
                            lastUserText = finalText
                            rendered.add(
                                LLMMessage(
                                    role = LLMMessage.Role.USER,
                                    content = finalText,
                                )
                            )
                        }
                    }
                }
                LLMMessage.Role.ASSISTANT -> {
                    val text = msg.content.trim().ifEmpty {
                        msg.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }.trim()
                    }
                    val calls = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                    val parts = mutableListOf<String>()
                    if (text.isNotEmpty()) parts.add(text)

                    for (call in calls) {
                        parts.add("[调用工具: ${call.name}(${call.input})]")
                    }

                    if (parts.isNotEmpty()) {
                        rendered.add(
                            LLMMessage(
                                role = LLMMessage.Role.ASSISTANT,
                                content = parts.joinToString("\n"),
                            )
                        )
                    }
                }
            }
        }

        // 保持 User 结尾，避免部分提供商将结尾 assistant 视为 prefill
        if (rendered.isNotEmpty() && rendered.last().role == LLMMessage.Role.ASSISTANT) {
            rendered.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = ADVISORY_INSTRUCTION,
                )
            )
        }

        if (rendered.isEmpty() && !lastUserText.isNullOrBlank()) {
            rendered.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = lastUserText,
                )
            )
        }

        return rendered
    }

    /**
     * 工具结果截断，保留 head + tail，防止塞满上下文
     * 对应 hermes _truncate_tool_result()
     */
    private fun truncateToolResult(text: String, budget: Int): String {
        if (text.length <= budget) return text
        val half = budget / 2
        val omitted = text.length - budget
        return "${text.take(half)}\n[... 省略 $omitted 字符 ...]\n${text.takeLast(half)}"
    }

    /**
     * 并发运行 MoA 顾问扇出
     */
    suspend fun runMoAFanOut(
        context: Context,
        providerRepository: ProviderRepository,
        advisorRoutes: List<AllowedModelRoute>,
        aggregatorRoute: AllowedModelRoute?,
        parentSessionId: String,
        history: List<LLMMessage>,
        timeoutSeconds: Long = 60L,
        onProgress: ((completed: Int, total: Int, currentLabel: String) -> Unit)? = null,
    ): MoAResult = withContext(Dispatchers.IO) {
        val total = advisorRoutes.size
        val advisoryMessages = buildAdvisoryMessages(history)
        var completedCount = 0

        // 1. 并发请求各顾问模型
        val deferredAdvisors = advisorRoutes.mapIndexed { index, route ->
            async {
                val handle = SubagentHandle(
                    parentSessionId = parentSessionId,
                    goal = "提供对当前任务状态的多视角专业分析与策略建议",
                    route = route,
                    role = SubagentRole.Advisor,
                    correlationId = "moa-advisor-${index + 1}",
                )
                SubagentRegistry.register(handle)

                val result = ChildAgentRunner.run(
                    context = context,
                    providerRepository = providerRepository,
                    handle = handle,
                    initialMessages = advisoryMessages,
                    timeoutSeconds = timeoutSeconds,
                )
                synchronized(this@MoAEngine) {
                    completedCount++
                    onProgress?.invoke(
                        completedCount,
                        total,
                        route.displayName ?: route.modelId
                    )
                }
                result
            }
        }

        val advisorResults = deferredAdvisors.awaitAll()

        // 2. 组装顾问输出
        val validAdvisorOutputs = advisorResults.filter { it.state == SubagentState.SUCCEEDED }
        val referenceBlocks = validAdvisorOutputs.mapIndexed { idx, res ->
            val label = res.handle.route.displayName ?: res.handle.route.modelId
            "【顾问 ${idx + 1} · $label】:\n${res.summary ?: "(无输出)"}"
        }.joinToString("\n\n")

        // 3. 聚合合成
        var synthesisText: String? = null
        if (aggregatorRoute != null && validAdvisorOutputs.isNotEmpty()) {
            val aggHandle = SubagentHandle(
                parentSessionId = parentSessionId,
                goal = "综合所有顾问的分析给出最优行动建议",
                route = aggregatorRoute,
                role = SubagentRole.Aggregator,
                correlationId = "moa-aggregator",
            )
            SubagentRegistry.register(aggHandle)

            val synthPrompt = """
                以下是各专业顾问针对当前任务给出的分析建议：
                
                $referenceBlocks
                
                请作为聚合器 (Aggregator)，对以上建议进行综合、提炼与去伪存真，指出共识、分歧、核心风险与推荐给主代理的具体行动步骤。
            """.trimIndent()

            val aggResult = ChildAgentRunner.run(
                context = context,
                providerRepository = providerRepository,
                handle = aggHandle,
                initialMessages = listOf(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = synthPrompt,
                    )
                ),
                timeoutSeconds = timeoutSeconds,
            )
            if (aggResult.state == SubagentState.SUCCEEDED) {
                synthesisText = aggResult.summary
            }
        }

        // 组装最终注入主代理的上下文
        val finalGuidance = buildString {
            appendLine("[团队混合专家 (MoA) 策略指引 — 请作为主代理解决任务的私有参考]")
            if (!synthesisText.isNullOrBlank()) {
                appendLine("【综合提炼决策】:")
                appendLine(synthesisText)
                appendLine()
            }
            appendLine("【各顾问参考意见】:")
            appendLine(referenceBlocks)
        }

        val totalTokens = advisorResults.sumOf { it.tokensUsed }
        val totalCost = advisorResults.sumOf { it.estimatedCostUsd ?: 0.0 }

        MoAResult(
            guidance = finalGuidance,
            advisorResults = advisorResults,
            synthesis = synthesisText,
            totalTokens = totalTokens,
            totalCostUsd = totalCost,
        )
    }

    data class MoAResult(
        val guidance: String,
        val advisorResults: List<SubagentResult>,
        val synthesis: String?,
        val totalTokens: Int,
        val totalCostUsd: Double,
    )
}
