package com.openminis.app.agent.subagent

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.MinisApp
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子代理执行器：轻量、安全、可控的独立子代理执行沙盒
 * 继承并重用现有 ProviderFactory、LLMProvider 和 Tools 架构
 */
object ChildAgentRunner {

    private const val TAG = "ChildAgentRunner"
    private const val MAX_DELEGATE_TURNS = 15

    const val REFERENCE_SYSTEM_PROMPT =
        "You are a reference advisor in a multi-agent collaborative process. You are " +
        "NOT the acting agent and you do NOT execute anything: you cannot call " +
        "tools, run commands, browse, or access files, and " +
        "you should not try to or apologize for being unable to. A separate " +
        "aggregator/orchestrator agent holds those capabilities and will take the " +
        "actual actions.\n\n" +
        "CRITICAL: You must NEVER claim or imply that you have executed a command, " +
        "downloaded a file, accessed a URL, or performed any action. You can only " +
        "analyze and advise based on the conversation context.\n\n" +
        "Your job is to give your most intelligent analysis of the task: understand the goal, " +
        "reason about the problem, and advise on what to do next. Surface the best approach, " +
        "concrete next steps, likely pitfalls and risks. Respond with your advice directly in prose."

    const val AGGREGATOR_SYSTEM_PROMPT =
        "You are the aggregator in a multi-agent collaborative process. Synthesize the " +
        "reference advisor responses into concise, actionable guidance for the main agent. " +
        "Focus on next steps, strategy, risks, and any disagreements. Do not call tools."

    /**
     * 构建子代理系统提示词
     */
    fun buildSystemPrompt(role: SubagentRole, goal: String, context: String?): String {
        return when (role) {
            SubagentRole.Advisor -> REFERENCE_SYSTEM_PROMPT
            SubagentRole.Aggregator -> AGGREGATOR_SYSTEM_PROMPT
            SubagentRole.Delegate -> {
                buildString {
                    appendLine("You are an autonomous subagent delegated to accomplish a specific goal:")
                    appendLine("GOAL: $goal")
                    if (!context.isNullOrBlank()) {
                        appendLine("CONTEXT:\n$context")
                    }
                    appendLine()
                    appendLine("You have tool execution capabilities (file reading, writing, editing, shell commands, and memory).")
                    appendLine("Focus strictly on accomplishing the goal efficiently. When done, output a comprehensive summary of your findings and solution.")
                }
            }
        }
    }

    /**
     * 构建针对角色的工具集
     * 规则：Advisor 与 Aggregator 绝对无工具；Delegate 拥有独立受控工具集（不包含递归子代理工具）
     */
    fun buildToolsForRole(role: SubagentRole): List<AgentToolDefinition> {
        return when (role) {
            SubagentRole.Advisor, SubagentRole.Aggregator -> emptyList()
            SubagentRole.Delegate -> {
                // 基础系统工具，不包含递归委托以杜绝死循环
                listOf(
                    FileReadTool.definition(),
                    FileWriteTool.definition(),
                    FileEditTool.definition(),
                )
            }
        }
    }

    /**
     * 解析并实例化子代理使用的 LLMProvider
     */
    fun resolveProvider(
        context: Context,
        providerRepository: ProviderRepository,
        route: AllowedModelRoute,
    ): LLMProvider {
        val conf = providerRepository.config.value
        val instance = (if (!route.instanceId.isNullOrBlank()) {
            conf.instances.find { it.id == route.instanceId }
        } else null) ?: conf.instances.find {
            it.isEnabled && (it.providerType.name.equals(route.providerId, ignoreCase = true) ||
                it.label.contains(route.providerId, ignoreCase = true))
        } ?: conf.instances.firstOrNull { it.isEnabled }
        ?: throw IllegalStateException("未能为子代理找到可用的服务商实例: ${route.providerId}")

        val apiKey = providerRepository.loadApiKey(instance.id)
            ?: throw IllegalStateException("服务商 '${instance.label}' 未配置有效 API Key")

        // 尝试从库里匹配已知 Model，否则构造
        val existingEntry = conf.modelEntries.find {
            it.providerInstanceId == instance.id && it.model.id.equals(route.modelId, ignoreCase = true)
        }
        val model = existingEntry?.model ?: LLMModel(
            id = route.modelId,
            displayName = route.displayName ?: route.modelId,
            provider = instance.providerType.name,
            contextWindow = route.contextWindow ?: 128_000,
        )

        return ProviderFactory.create(instance, apiKey, model, context)
    }

    /**
     * 运行子代理任务
     */
    suspend fun run(
        context: Context,
        providerRepository: ProviderRepository,
        handle: SubagentHandle,
        initialMessages: List<LLMMessage>? = null,
        timeoutSeconds: Long = 120L,
        onProgress: ((String) -> Unit)? = null,
    ): SubagentResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var totalTokens = 0
        var totalApiCalls = 0

        // 异步创建该子代理对应的独立会话，标记来源为 subagent:$parentSessionId
        val chatRepo = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull
        val childSessionId = try {
            val roleName = when (handle.role) {
                SubagentRole.Advisor -> "顾问"
                SubagentRole.Delegate -> "委托"
                SubagentRole.Aggregator -> "聚合"
            }
            val shortGoal = handle.goal.take(24).replace("\n", " ")
            chatRepo?.createSession(
                modelId = handle.route.modelId,
                title = "🤖 $roleName: ${handle.route.displayName ?: handle.route.modelId} - $shortGoal",
                memoryEnabled = false,
            )?.let { session ->
                // 更新 source 与 category 标记为子代理
                chatRepo.updateSessionTitleAndCategory(
                    id = session.id,
                    title = session.title ?: "子代理任务",
                    category = "subagent",
                )
                // 标记 source 字段关联父会话
                try {
                    chatRepo.updateSessionSource(session.id, "subagent:${handle.parentSessionId}")
                } catch (_: Exception) {}
                // 绑定模型条目，以便进入子会话后精确恢复 Provider 和模型参数
                try {
                    val entry = providerRepository.allVisibleEntries().find {
                        it.model.id == handle.route.modelId
                    }
                    val bindingJson = JSONObject().apply {
                        put("type", "entry")
                        put("entryId", entry?.id ?: "")
                        put("modelId", handle.route.modelId)
                    }.toString()
                    chatRepo.updateSessionBinding(session.id, bindingJson, handle.route.modelId)
                } catch (_: Exception) {}
                session.id
            }
        } catch (e: Exception) {
            AppLogger.info(TAG, "Subagent session creation skipped: ${e.message}")
            null
        }

        if (childSessionId != null) {
            SubagentRegistry.bindChildSession(handle.id, childSessionId)
        }

        SubagentRegistry.updateState(handle.id, SubagentState.RUNNING)
        SubagentRegistry.updateProgress(handle.id, "正在初始化子代理", "初始化模型: ${handle.route.displayName ?: handle.route.modelId}")
        onProgress?.invoke("正在初始化子代理 (${handle.route.displayName ?: handle.route.modelId})...")

        try {
            withTimeout(timeoutSeconds * 1000L) {
                val provider = resolveProvider(context, providerRepository, handle.route)
                val systemPrompt = buildSystemPrompt(handle.role, handle.goal, handle.context)
                val tools = buildToolsForRole(handle.role)

                // 初始化历史
                val history = mutableListOf<LLMMessage>()
                val promptText = if (!handle.context.isNullOrBlank()) {
                    "任务要求:\n${handle.goal}\n\n上下文信息:\n${handle.context}"
                } else {
                    "任务要求:\n${handle.goal}"
                }

                if (initialMessages != null && initialMessages.isNotEmpty()) {
                    history.addAll(initialMessages)
                } else {
                    history.add(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = promptText,
                        )
                    )
                }

                // 确保子会话立即落盘第一条 User 任务提问，防止出现空会话导致退出时被 cleanup 误删
                if (childSessionId != null) {
                    try {
                        val taskDesc = if (initialMessages != null && initialMessages.isNotEmpty()) {
                            val lastUser = initialMessages.lastOrNull { it.role == LLMMessage.Role.USER }?.content
                            if (!lastUser.isNullOrBlank()) {
                                "【协同任务】: ${handle.goal}\n\n【用户提问】:\n$lastUser"
                            } else {
                                promptText
                            }
                        } else {
                            promptText
                        }
                        val partsJson = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("value", taskDesc)
                                put("text", taskDesc)
                            })
                        }.toString()
                        chatRepo?.appendMessage(childSessionId, "user", partsJson)
                    } catch (_: Exception) {}
                }

                var turn = 0
                var finalOutput = ""
                val maxTurns = if (handle.role == SubagentRole.Delegate) MAX_DELEGATE_TURNS else 1

                while (turn < maxTurns) {
                    turn++
                    totalApiCalls++

                    // 检查是否存在人工纠偏干预指令
                    val interventionChannel = SubagentRegistry.getOrCreateInterventionChannel(handle.id)
                    val pendingIntervention = interventionChannel.tryReceive().getOrNull()
                    if (!pendingIntervention.isNullOrBlank()) {
                        val interventionText = "【用户人工干预指令】: $pendingIntervention"
                        history.add(LLMMessage(role = LLMMessage.Role.USER, content = interventionText))
                        SubagentRegistry.updateProgress(handle.id, "执行用户干预指令", interventionText)
                        if (childSessionId != null) {
                            try {
                                val partsJson = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("value", interventionText)
                                        put("text", interventionText)
                                    })
                                }.toString()
                                chatRepo?.appendMessage(childSessionId, "user", partsJson)
                            } catch (_: Exception) {}
                        }
                    }

                    SubagentRegistry.updateProgress(handle.id, "正在思考 (第 $turn 轮)", "启动第 $turn 轮模型生成")
                    onProgress?.invoke("子代理正在思考 (第 $turn 轮)...")

                    val turnTextBuilder = StringBuilder()
                    val toolCalls = mutableListOf<AgentContentPart.ToolUse>()

                    provider.streamMessage(
                        messages = history,
                        systemPrompt = systemPrompt,
                        maxTokens = 4096,
                        tools = tools,
                    ).collect { chunk ->
                        when (chunk) {
                            is LLMStreamChunk.Text -> {
                                turnTextBuilder.append(chunk.text)
                            }
                            is LLMStreamChunk.ToolCallComplete -> {
                                toolCalls.add(
                                    AgentContentPart.ToolUse(
                                        id = chunk.id,
                                        name = chunk.name,
                                        input = chunk.args,
                                        thoughtSignature = chunk.thoughtSignature,
                                    )
                                )
                            }
                            is LLMStreamChunk.Usage -> {
                                totalTokens += chunk.usage.inputTokens + chunk.usage.outputTokens
                            }
                            else -> {}
                        }
                    }

                    val assistantText = turnTextBuilder.toString()
                    finalOutput = assistantText

                    // 记录进入历史
                    val contentParts = mutableListOf<AgentContentPart>()
                    if (assistantText.isNotEmpty()) {
                        contentParts.add(AgentContentPart.Text(assistantText))
                    }
                    contentParts.addAll(toolCalls)

                    history.add(
                        LLMMessage(
                            role = LLMMessage.Role.ASSISTANT,
                            content = assistantText,
                            contentParts = contentParts,
                        )
                    )

                    // 持久化助手回复与工具调用到子会话
                    if (childSessionId != null && (assistantText.isNotBlank() || toolCalls.isNotEmpty())) {
                        try {
                            val partsArray = JSONArray()
                            if (assistantText.isNotBlank()) {
                                partsArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("value", assistantText)
                                    put("text", assistantText)
                                })
                            }
                            for (call in toolCalls) {
                                partsArray.put(JSONObject().apply {
                                    put("type", "toolUse")
                                    put("value", JSONObject().apply {
                                        put("toolUseId", call.id)
                                        put("name", call.name)
                                        put("input", call.input.toString())
                                        put("description", "调用工具: ${call.name}")
                                    })
                                })
                            }
                            chatRepo?.appendMessage(childSessionId, "assistant", partsArray.toString())
                        } catch (_: Exception) {}
                    }

                    SubagentRegistry.updateProgress(
                        handle.id,
                        currentStep = if (toolCalls.isNotEmpty()) "执行工具 (${toolCalls.joinToString { it.name }})" else "思考完成",
                        logEntry = if (assistantText.isNotBlank()) "助手回复: ${assistantText.take(120)}..." else null,
                    )

                    // 如果没有工具调用或者不是 Delegate 模式，直接完成
                    if (toolCalls.isEmpty() || handle.role != SubagentRole.Delegate) {
                        break
                    }

                    // 执行工具调用（只支持基础安全工具）
                    for (call in toolCalls) {
                        onProgress?.invoke("子代理执行工具: ${call.name}...")
                        SubagentRegistry.updateProgress(handle.id, "执行工具: ${call.name}", "调用工具: ${call.name}")
                        val resultStr = executeSafeTool(context, handle.parentSessionId, call.name, call.input.toString())
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = resultStr,
                                contentParts = listOf(
                                    AgentContentPart.ToolResult(
                                        id = call.id,
                                        name = call.name,
                                        content = resultStr,
                                    )
                                ),
                            )
                        )

                        if (childSessionId != null) {
                            try {
                                val partsJson = JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "toolResult")
                                        put("value", JSONObject().apply {
                                            put("toolUseId", call.id)
                                            put("output", resultStr.take(1000))
                                            put("success", true)
                                        })
                                        put("tool_use_id", call.id)
                                        put("content", resultStr.take(1000))
                                    })
                                }.toString()
                                chatRepo?.appendMessage(childSessionId, "user", partsJson)
                            } catch (_: Exception) {}
                        }
                    }
                }

                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                // 估算费用：以 gpt-4o-mini / deepseek 水平简单估算 (~$0.2 / 1M tokens)
                val estimatedCost = (totalTokens / 1_000_000.0) * 0.20

                val result = SubagentResult(
                    handle = handle,
                    state = SubagentState.SUCCEEDED,
                    summary = finalOutput.ifBlank { "(子代理已完成任务)" },
                    startedAt = startTime,
                    completedAt = System.currentTimeMillis(),
                    apiCalls = totalApiCalls,
                    durationSeconds = duration,
                    tokensUsed = totalTokens,
                    estimatedCostUsd = estimatedCost,
                )
                SubagentRegistry.updateState(handle.id, SubagentState.SUCCEEDED, result)
                SubagentRegistry.updateProgress(handle.id, "已完成", "任务完成，总耗时 ${String.format("%.1f", duration)}s")
                result
            }
        } catch (e: CancellationException) {
            val result = SubagentResult(
                handle = handle,
                state = SubagentState.CANCELLED,
                summary = "[子代理已被取消]",
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                apiCalls = totalApiCalls,
                tokensUsed = totalTokens,
                errorMessage = e.message,
            )
            SubagentRegistry.updateState(handle.id, SubagentState.CANCELLED, result)
            SubagentRegistry.updateProgress(handle.id, "已取消", "任务被取消: ${e.message}")
            result
        } catch (e: Exception) {
            AppLogger.error(TAG, "Subagent ${handle.id} failed: ${e.message}")
            val result = SubagentResult(
                handle = handle,
                state = SubagentState.FAILED,
                summary = "[子代理执行出错: ${e.message}]",
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                apiCalls = totalApiCalls,
                tokensUsed = totalTokens,
                errorMessage = e.message ?: "Unknown error",
            )
            SubagentRegistry.updateState(handle.id, SubagentState.FAILED, result)
            SubagentRegistry.updateProgress(handle.id, "执行出错", "错误: ${e.message}")
            result
        }
    }

    /**
     * 对已完成/已中断的子代理进行人工干预并续跑
     */
    suspend fun resumeWithIntervention(
        context: Context,
        providerRepository: ProviderRepository,
        handle: SubagentHandle,
        existingHistory: List<LLMMessage>,
        intervention: String,
        timeoutSeconds: Long = 120L,
        onProgress: ((String) -> Unit)? = null,
    ): SubagentResult {
        val interventionMsg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "【用户人工干预指令】: $intervention",
        )
        val newHistory = existingHistory + interventionMsg
        return run(
            context = context,
            providerRepository = providerRepository,
            handle = handle,
            initialMessages = newHistory,
            timeoutSeconds = timeoutSeconds,
            onProgress = onProgress,
        )
    }

    private fun executeSafeTool(context: Context, parentSessionId: String, name: String, arguments: String): String {
        return try {
            when (name) {
                "file_read" -> FileReadTool.execute(arguments, parentSessionId, context).output
                "file_write" -> FileWriteTool.execute(arguments, parentSessionId, context).output
                "file_edit" -> FileEditTool.execute(arguments, parentSessionId, context).output
                else -> "Error: Tool '$name' is not permitted for subagent execution."
            }
        } catch (e: Exception) {
            "Tool execution failed: ${e.message}"
        }
    }
}
