package com.openminis.app.tools

import android.content.Context
import com.openminis.app.agent.subagent.ChildAgentRunner
import com.openminis.app.agent.subagent.SubagentHandle
import com.openminis.app.agent.subagent.SubagentRegistry
import com.openminis.app.agent.subagent.SubagentRole
import com.openminis.app.agent.subagent.SubagentState
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SubagentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务委托工具与模型列表查询工具
 * 对应 deepseek-harness tool-subagent 与 hermes delegate_tool
 */
object DelegateTaskTool {

    const val TOOL_DELEGATE = "delegate_task"
    const val TOOL_LIST_MODELS = "list_subagent_models"

    fun delegateDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_DELEGATE,
        description = "将特定子任务委派给独立的子代理 (Subagent) 并发或独立执行。" +
            "子代理拥有自己的执行循环与文件/命令工具，执行完毕后将其结论作为工具执行结果返回给主代理。" +
            "可以通过 model 或 group 参数选择子代理使用的模型或模型组（必须在用户设置的允许列表中，调用 list_subagent_models 可查看）。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "操作标题，如 '委托子代理分析测试覆盖率'"),
            "goal" to AgentToolParam("string", "委托子代理完成的具体目标与任务描述"),
            "context" to AgentToolParam("string", "提供给子代理的额外背景、约束或参考信息"),
            "model" to AgentToolParam("string", "可选：指定子代理运行的模型ID。留空则使用默认配置的模型"),
            "group" to AgentToolParam("string", "可选：指定从哪个模型组(如 '代码组', '推理组')中分派子代理模型"),
        ),
        required = listOf("tool_title", "goal"),
        propertyOrdering = listOf("tool_title", "goal", "context", "model", "group"),
    )

    fun listModelsDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_LIST_MODELS,
        description = "查询当前系统中的模型组与允许子代理调用的模型白名单，以在 delegate_task 中按模型组精准分派任务，并避免调用未授权的高价模型。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "操作标题，如 '查询可用子代理模型'"),
        ),
        required = listOf("tool_title"),
    )

    suspend fun executeDelegate(
        context: Context,
        providerRepository: ProviderRepository,
        subagentRepository: SubagentRepository,
        parentSessionId: String,
        currentEntry: ModelEntry?,
        args: JSONObject,
        sessionBoundGroupId: String? = null,
        onProgress: ((String) -> Unit)? = null,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val goal = args.optString("goal").trim()
        if (goal.isEmpty()) {
            return@withContext ToolExecutionResult(output = "Error: 'goal' parameter must not be empty.", success = false)
        }
        val extraContext = args.optString("context").ifBlank { null }
        val modelArg = args.optString("model").ifBlank { null }
        val groupArg = args.optString("group").ifBlank { null } ?: sessionBoundGroupId

        try {
            // 1. 严格模型路由解析与白名单校验
            val route = subagentRepository.resolveChildRoute(
                requestedModel = modelArg,
                requestedGroup = groupArg,
                fallbackEntry = currentEntry,
            )

            // 2. 注册并调度子代理
            val handle = SubagentHandle(
                parentSessionId = parentSessionId,
                goal = goal,
                context = extraContext,
                route = route,
                role = SubagentRole.Delegate,
                depth = 1,
            )
            SubagentRegistry.register(handle)

            // 3. 执行子代理任务
            val result = ChildAgentRunner.run(
                context = context,
                providerRepository = providerRepository,
                handle = handle,
                timeoutSeconds = subagentRepository.config.value.timeoutSeconds,
                onProgress = onProgress,
            )

            if (result.state == SubagentState.SUCCEEDED) {
                val output = buildString {
                    appendLine("【子代理 (${route.displayName ?: route.modelId}) 完成任务】:")
                    appendLine(result.summary ?: "(任务完成但无文本返回)")
                    appendLine()
                    appendLine("[执行信息: 耗时 ${String.format("%.1f", result.durationSeconds)}秒, 交互 ${result.apiCalls} 轮, 消耗 ${result.tokensUsed} tokens]")
                }
                ToolExecutionResult(output = output, success = true)
            } else {
                ToolExecutionResult(
                    output = "子代理执行失败 [${result.state}]: ${result.errorMessage ?: result.summary ?: "未知错误"}",
                    success = false,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "委托子代理启动失败: ${e.message}",
                success = false,
            )
        }
    }

    fun executeListModels(
        subagentRepository: SubagentRepository,
    ): ToolExecutionResult {
        val config = subagentRepository.config.value
        val policy = config.modelRoutePolicy
        val candidates = subagentRepository.getAllAvailableCandidateEntries()
        val groups = subagentRepository.getAllModelGroups()

        val jsonResult = JSONObject().apply {
            put("source_mode", policy.sourceMode.name)
            put("default_subagent_model", policy.defaultRoute?.displayName ?: policy.defaultRoute?.modelId ?: "跟随主会话模型")
            put("bound_group_id", policy.targetGroupId ?: "未指定")

            val groupsArray = JSONArray()
            groups.forEach { group ->
                val memberEntries = candidates.filter { it.id in group.memberEntryIds }
                groupsArray.put(
                    JSONObject().apply {
                        put("group_id", group.id)
                        put("group_name", group.name)
                        put("member_count", memberEntries.size)
                        val membersArr = JSONArray()
                        memberEntries.forEach { me ->
                            membersArr.put(
                                JSONObject().apply {
                                    put("model_id", me.model.id)
                                    put("display_name", me.model.displayName)
                                    put("provider", me.model.provider)
                                }
                            )
                        }
                        put("models", membersArr)
                    }
                )
            }
            put("available_model_groups", groupsArray)

            val allowedArray = JSONArray()
            val effectiveAllowed = policy.allowedRoutes ?: candidates.map { subagentRepository.entryToRoute(it) }
            effectiveAllowed.forEach { route ->
                allowedArray.put(
                    JSONObject().apply {
                        put("model_id", route.modelId)
                        put("provider", route.providerId)
                        put("display_name", route.displayName ?: route.modelId)
                    }
                )
            }
            put("allowed_models", allowedArray)
        }

        return ToolExecutionResult(
            output = jsonResult.toString(2),
            success = true,
        )
    }
}
