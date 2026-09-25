package com.openminis.app.agent.subagent

import com.openminis.app.data.model.LLMMessage
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 团队协作模式枚举
 */
enum class TeamworkMode(val label: String, val description: String) {
    DISABLED("关闭", "单模型独立交互，不启动子代理与 MoA 混合专家"),
    MOA("混合专家 (MoA)", "并发启动多个顾问模型进行独立分析，由聚合模型综合提炼最优方案后再由主代理执行"),
    DELEGATE("任务委托 (Delegate)", "主模型根据任务复杂度自主将子任务分派给独立的子代理协同完成"),
    FREE("自由协同 (Free)", "主模型自主决定何时、委派多少个子代理，严格遵循子代理白名单约束"),
}

/**
 * 子代理数量策略
 */
sealed class AgentCount {
    data class Fixed(val count: Int) : AgentCount()
    object Auto : AgentCount()

    fun resolvedCount(defaultCount: Int = 3): Int = when (this) {
        is Fixed -> count.coerceIn(1, 8)
        is Auto -> defaultCount.coerceIn(1, 8)
    }
}

/**
 * 子代理角色类型
 */
enum class SubagentRole {
    Advisor,     // MoA 顾问：只分析给出建议，无工具权限
    Delegate,    // 委托执行：独立执行特定任务，拥有完整工具集
    Aggregator,  // 聚合器：汇总多个顾问建议合成最终策略，无工具权限
}

/**
 * 子代理生命周期状态
 */
enum class SubagentState {
    PENDING,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, INTERRUPTED, CANCELLED)
}

/**
 * 子代理模型来源策略模式
 */
enum class SubagentModelSourceMode(val label: String, val description: String) {
    MODEL_GROUP("按模型组调度", "优先从选定的模型组中分派各个子代理与顾问"),
    CUSTOM_ROUTES("自定义指定模型", "为各个顾问与子代理显式挑选具体的模型"),
    WHITELIST("白名单分类筛选", "在按模型组与服务商分类的白名单中自由勾选允许调度的模型"),
}

/**
 * 允许的模型路由条目（与 Provider 和 ModelEntry 对齐）
 */
@Serializable
data class AllowedModelRoute(
    val providerId: String,          // e.g. "openai", "anthropic", "gemini", "orcarouter"
    val modelId: String,             // e.g. "gpt-4o-mini", "claude-haiku-4-5"
    val displayName: String? = null,
    val instanceId: String? = null,  // 绑定的 ProviderInstance.id
    val contextWindow: Int? = null,
) {
    val key: String get() = "$providerId/$modelId"
}

/**
 * 模型路由控制策略（防乱调用、防超额开销核心机制）
 */
@Serializable
data class ModelRoutePolicy(
    val sourceMode: SubagentModelSourceMode = SubagentModelSourceMode.MODEL_GROUP,
    val allowedRoutes: List<AllowedModelRoute>? = null, // 白名单；null 表示使用默认路由
    val defaultRoute: AllowedModelRoute? = null,        // 默认子代理模型 / 聚合模型
    val targetGroupId: String? = null,                  // 选定的目标模型组ID
    val customAdvisorRoutes: List<AllowedModelRoute>? = null, // 自定义显式指定的顾问模型列表
    val allowChildDelegation: Boolean = false,          // 是否允许子代理递归创建子代理（默认关闭）
) {
    /**
     * 校验请求的路由是否合法。
     * 若不合法抛出 SubagentPolicyViolationException。
     */
    fun assertAllowed(requestedRoute: AllowedModelRoute) {
        if (sourceMode == SubagentModelSourceMode.WHITELIST) {
            val effectiveAllowed = allowedRoutes
            if (effectiveAllowed.isNullOrEmpty()) {
                throw SubagentPolicyViolationException("当前为白名单模式，但未配置任何允许的子代理模型，请在设置中勾选白名单。")
            }
            val matched = effectiveAllowed.any {
                (it.modelId.equals(requestedRoute.modelId, ignoreCase = true)) &&
                    (it.providerId.isEmpty() || requestedRoute.providerId.isEmpty() ||
                        it.providerId.equals(requestedRoute.providerId, ignoreCase = true))
            }
            if (!matched) {
                throw SubagentPolicyViolationException(
                    "模型 '${requestedRoute.modelId}' 不在允许的子代理模型白名单内。" +
                        "允许的模型包括: ${effectiveAllowed.joinToString { it.displayName ?: it.modelId }}"
                )
            }
            return
        }

        // 其它模式下若配置了白名单，也需作为合规限制
        val effectiveAllowed = allowedRoutes
        if (effectiveAllowed != null && effectiveAllowed.isNotEmpty()) {
            val matched = effectiveAllowed.any {
                (it.modelId.equals(requestedRoute.modelId, ignoreCase = true)) &&
                    (it.providerId.isEmpty() || requestedRoute.providerId.isEmpty() ||
                        it.providerId.equals(requestedRoute.providerId, ignoreCase = true))
            }
            if (!matched) {
                throw SubagentPolicyViolationException(
                    "模型 '${requestedRoute.modelId}' 不在允许的子代理模型白名单内。" +
                        "允许的模型包括: ${effectiveAllowed.joinToString { it.displayName ?: it.modelId }}"
                )
            }
        }
    }

    /**
     * 检查请求的路由是否允许（不抛异常）
     */
    fun isRouteAllowed(requestedRoute: AllowedModelRoute): Boolean {
        return runCatching { assertAllowed(requestedRoute) }.isSuccess
    }
}

class SubagentPolicyViolationException(message: String) : IllegalArgumentException(message)

/**
 * 子代理执行配置
 */
data class SubagentConfig(
    val mode: TeamworkMode = TeamworkMode.DISABLED,
    val agentCount: AgentCount = AgentCount.Fixed(3),
    val modelRoutePolicy: ModelRoutePolicy = ModelRoutePolicy(),
    val maxDepth: Int = 1,
    val timeoutSeconds: Long = 120L,
)

/**
 * 活跃子代理句柄
 */
@androidx.compose.runtime.Immutable
data class SubagentHandle(
    val id: String = UUID.randomUUID().toString(),
    val parentSessionId: String,
    val correlationId: String? = null,
    val goal: String,
    val context: String? = null,
    val route: AllowedModelRoute,
    val role: SubagentRole,
    val depth: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 子代理执行结果（包含明细与费用统计）
 */
@androidx.compose.runtime.Immutable
data class SubagentResult(
    val handle: SubagentHandle,
    val state: SubagentState,
    val summary: String? = null,
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val apiCalls: Int = 0,
    val durationSeconds: Double = 0.0,
    val errorMessage: String? = null,
    val tokensUsed: Int = 0,
    val estimatedCostUsd: Double? = null,
)

/**
 * 启动子代理请求
 */
data class SubagentLaunchRequest(
    val goal: String,
    val context: String? = null,
    val role: SubagentRole = SubagentRole.Delegate,
    val preferredRoute: AllowedModelRoute? = null,
    val timeoutSeconds: Long? = null,
    val correlationId: String? = null,
    val inheritedHistory: List<LLMMessage>? = null,
)
