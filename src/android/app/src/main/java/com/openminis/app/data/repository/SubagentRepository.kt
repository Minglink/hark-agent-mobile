package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.agent.subagent.AgentCount
import com.openminis.app.agent.subagent.AllowedModelRoute
import com.openminis.app.agent.subagent.ModelRoutePolicy
import com.openminis.app.agent.subagent.SubagentConfig
import com.openminis.app.agent.subagent.SubagentModelSourceMode
import com.openminis.app.agent.subagent.SubagentPolicyViolationException
import com.openminis.app.agent.subagent.TeamworkMode
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 子代理配置持久化管理仓库
 */
class SubagentRepository(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SubagentConfig> = _config.asStateFlow()

    private fun loadConfig(): SubagentConfig {
        val modeStr = prefs.getString(KEY_MODE, TeamworkMode.DISABLED.name)
        val mode = runCatching { TeamworkMode.valueOf(modeStr ?: TeamworkMode.DISABLED.name) }.getOrDefault(TeamworkMode.DISABLED)

        val countType = prefs.getString(KEY_AGENT_COUNT_TYPE, "fixed")
        val countVal = prefs.getInt(KEY_AGENT_COUNT_VALUE, 3)
        val agentCount = if (countType == "auto") AgentCount.Auto else AgentCount.Fixed(countVal)

        val sourceModeStr = prefs.getString(KEY_SOURCE_MODE, SubagentModelSourceMode.MODEL_GROUP.name)
        val sourceMode = runCatching {
            SubagentModelSourceMode.valueOf(sourceModeStr ?: SubagentModelSourceMode.MODEL_GROUP.name)
        }.getOrDefault(SubagentModelSourceMode.MODEL_GROUP)

        val defaultRouteJson = prefs.getString(KEY_DEFAULT_ROUTE, null)
        val defaultRoute: AllowedModelRoute? = defaultRouteJson?.let {
            runCatching { json.decodeFromString<AllowedModelRoute>(it) }.getOrNull()
        }

        val allowedRoutesJson = prefs.getString(KEY_ALLOWED_ROUTES, null)
        val allowedRoutes: List<AllowedModelRoute>? = allowedRoutesJson?.let {
            runCatching { json.decodeFromString<List<AllowedModelRoute>>(it) }.getOrNull()
        }

        val customAdvisorsJson = prefs.getString(KEY_CUSTOM_ADVISORS, null)
        val customAdvisors: List<AllowedModelRoute>? = customAdvisorsJson?.let {
            runCatching { json.decodeFromString<List<AllowedModelRoute>>(it) }.getOrNull()
        }

        val targetGroupId = prefs.getString(KEY_TARGET_GROUP_ID, null)
        val maxDepth = prefs.getInt(KEY_MAX_DEPTH, 1)
        val timeoutSec = prefs.getLong(KEY_TIMEOUT_SEC, 120L)

        return SubagentConfig(
            mode = mode,
            agentCount = agentCount,
            modelRoutePolicy = ModelRoutePolicy(
                sourceMode = sourceMode,
                allowedRoutes = allowedRoutes,
                defaultRoute = defaultRoute,
                targetGroupId = targetGroupId,
                customAdvisorRoutes = customAdvisors,
                allowChildDelegation = false,
            ),
            maxDepth = maxDepth,
            timeoutSeconds = timeoutSec,
        )
    }

    fun updateConfig(newConfig: SubagentConfig) {
        prefs.edit().apply {
            putString(KEY_MODE, newConfig.mode.name)
            when (val count = newConfig.agentCount) {
                is AgentCount.Fixed -> {
                    putString(KEY_AGENT_COUNT_TYPE, "fixed")
                    putInt(KEY_AGENT_COUNT_VALUE, count.count)
                }
                is AgentCount.Auto -> {
                    putString(KEY_AGENT_COUNT_TYPE, "auto")
                }
            }

            putString(KEY_SOURCE_MODE, newConfig.modelRoutePolicy.sourceMode.name)

            if (newConfig.modelRoutePolicy.defaultRoute != null) {
                putString(KEY_DEFAULT_ROUTE, json.encodeToString(newConfig.modelRoutePolicy.defaultRoute))
            } else {
                remove(KEY_DEFAULT_ROUTE)
            }

            if (newConfig.modelRoutePolicy.allowedRoutes != null) {
                putString(KEY_ALLOWED_ROUTES, json.encodeToString(newConfig.modelRoutePolicy.allowedRoutes))
            } else {
                remove(KEY_ALLOWED_ROUTES)
            }

            if (newConfig.modelRoutePolicy.customAdvisorRoutes != null) {
                putString(KEY_CUSTOM_ADVISORS, json.encodeToString(newConfig.modelRoutePolicy.customAdvisorRoutes))
            } else {
                remove(KEY_CUSTOM_ADVISORS)
            }

            putString(KEY_TARGET_GROUP_ID, newConfig.modelRoutePolicy.targetGroupId)
            putInt(KEY_MAX_DEPTH, newConfig.maxDepth)
            putLong(KEY_TIMEOUT_SEC, newConfig.timeoutSeconds)
            apply()
        }
        _config.value = newConfig
    }

    fun setSourceMode(mode: SubagentModelSourceMode) {
        val current = _config.value
        val updated = current.copy(
            modelRoutePolicy = current.modelRoutePolicy.copy(sourceMode = mode)
        )
        updateConfig(updated)
    }

    fun setDefaultRoute(route: AllowedModelRoute?) {
        val current = _config.value
        val updated = current.copy(
            modelRoutePolicy = current.modelRoutePolicy.copy(defaultRoute = route)
        )
        updateConfig(updated)
    }

    fun setAllowedRoutes(routes: List<AllowedModelRoute>?) {
        val current = _config.value
        val updated = current.copy(
            modelRoutePolicy = current.modelRoutePolicy.copy(allowedRoutes = routes)
        )
        updateConfig(updated)
    }

    fun setCustomAdvisorRoutes(routes: List<AllowedModelRoute>?) {
        val current = _config.value
        val updated = current.copy(
            modelRoutePolicy = current.modelRoutePolicy.copy(customAdvisorRoutes = routes)
        )
        updateConfig(updated)
    }

    fun setTargetGroupId(groupId: String?) {
        val current = _config.value
        val updated = current.copy(
            modelRoutePolicy = current.modelRoutePolicy.copy(targetGroupId = groupId)
        )
        updateConfig(updated)
    }

    fun setMode(mode: TeamworkMode) {
        val current = _config.value
        val updated = current.copy(mode = mode)
        updateConfig(updated)
    }

    fun setAgentCount(count: AgentCount) {
        val current = _config.value
        val updated = current.copy(agentCount = count)
        updateConfig(updated)
    }

    /**
     * 获取系统中所有可配置为子代理的模型条目
     */
    fun getAllAvailableCandidateEntries(): List<ModelEntry> {
        val conf = providerRepository.config.value
        val enabledInstanceIds = conf.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return conf.modelEntries.filter { it.providerInstanceId in enabledInstanceIds }
    }

    /**
     * 获取所有可用的模型组
     */
    fun getAllModelGroups(): List<ModelGroup> {
        return providerRepository.config.value.modelGroups
    }

    /**
     * 按模型组对可用模型进行分类提取
     */
    fun getGroupedCandidateEntries(): Map<ModelGroup, List<ModelEntry>> {
        val all = getAllAvailableCandidateEntries()
        val groups = getAllModelGroups()
        val result = linkedMapOf<ModelGroup, List<ModelEntry>>()
        for (group in groups) {
            val members = all.filter { it.id in group.memberEntryIds }
            if (members.isNotEmpty()) {
                result[group] = members
            }
        }
        return result
    }

    /**
     * 获取未加入任何模型组的候选模型，按服务商实例分类展示
     */
    fun getUngroupedCandidateEntries(): Map<String, List<ModelEntry>> {
        val all = getAllAvailableCandidateEntries()
        val groups = getAllModelGroups()
        val allGroupMemberIds = groups.flatMap { it.memberEntryIds }.toSet()
        val ungrouped = all.filter { it.id !in allGroupMemberIds }
        val instances = providerRepository.config.value.instances.associateBy { it.id }
        return ungrouped.groupBy { entry ->
            val instance = instances[entry.providerInstanceId]
            if (instance != null) {
                val label = instance.label.trim()
                if (label.isNotEmpty()) {
                    "$label (${instance.providerType.displayName})"
                } else {
                    instance.providerType.displayName
                }
            } else {
                entry.model.provider.ifBlank { "未分类服务商" }
            }
        }
    }

    /**
     * 根据候选模型条目转化为 AllowedModelRoute
     */
    fun entryToRoute(entry: ModelEntry): AllowedModelRoute {
        return AllowedModelRoute(
            providerId = entry.model.provider,
            modelId = entry.model.id,
            displayName = "${entry.model.displayName} (${entry.model.provider})",
            instanceId = entry.providerInstanceId,
            contextWindow = entry.model.contextWindow,
        )
    }

    /**
     * 专为 MoA (Mixture of Agents) 混合专家并发解析各顾问模型与聚合器
     *
     * 优先级规则：
     * 1. 自定义指定模式（CUSTOM_ROUTES）：直接使用用户挑选的特定顾问模型列表；
     * 2. 白名单模式（WHITELIST）：严格只从已勾选的白名单中分配模型，杜绝未在白名单中的模型被调用；
     * 3. 模型组调度模式（MODEL_GROUP）：从指定的 targetGroupId 提取成员（若配置白名单则叠加白名单过滤）；
     * 4. 兜底方案：从系统可用且符合策略的模型中分配。
     */
    fun resolveMoARoutes(
        targetGroupId: String? = null,
        count: Int = 3,
        fallbackEntry: ModelEntry? = null,
    ): Pair<List<AllowedModelRoute>, AllowedModelRoute?> {
        val policy = _config.value.modelRoutePolicy
        val allEntries = getAllAvailableCandidateEntries()
        val groups = getAllModelGroups()

        // 1. 自定义指定模式
        if (policy.sourceMode == SubagentModelSourceMode.CUSTOM_ROUTES && !policy.customAdvisorRoutes.isNullOrEmpty()) {
            val advisors = policy.customAdvisorRoutes.take(count)
            val aggregator = policy.defaultRoute ?: advisors.firstOrNull() ?: fallbackEntry?.let { entryToRoute(it) }
            return Pair(advisors, aggregator)
        }

        // 2. 白名单模式：严格仅允许白名单模型
        if (policy.sourceMode == SubagentModelSourceMode.WHITELIST) {
            val allowed = policy.allowedRoutes
            if (allowed.isNullOrEmpty()) {
                throw SubagentPolicyViolationException("当前为白名单模式，但白名单中未勾选任何允许的模型，请在【子代理协同设置】中勾选白名单。")
            }
            val advisors = if (allowed.size >= count) {
                allowed.take(count)
            } else {
                val list = mutableListOf<AllowedModelRoute>()
                while (list.size < count) {
                    list.addAll(allowed)
                }
                list.take(count)
            }
            val aggregator = policy.defaultRoute?.takeIf { policy.isRouteAllowed(it) }
                ?: allowed.firstOrNull()
            return Pair(advisors, aggregator)
        }

        // 3. 模型组模式或传入了具体的模型组
        val effectiveGroupId = targetGroupId ?: policy.targetGroupId
        if (!effectiveGroupId.isNullOrBlank()) {
            val group = groups.find {
                it.id.equals(effectiveGroupId, ignoreCase = true) ||
                    it.name.equals(effectiveGroupId, ignoreCase = true)
            }
            if (group != null) {
                val groupEntries = allEntries.filter { it.id in group.memberEntryIds }
                if (groupEntries.isNotEmpty()) {
                    var groupRoutes = groupEntries.map { entryToRoute(it) }
                    // 叠加白名单过滤（如果配置了额外白名单）
                    if (!policy.allowedRoutes.isNullOrEmpty()) {
                        val filtered = groupRoutes.filter { policy.isRouteAllowed(it) }
                        if (filtered.isNotEmpty()) {
                            groupRoutes = filtered
                        }
                    }
                    val advisors = if (groupRoutes.size >= count) {
                        groupRoutes.take(count)
                    } else {
                        val list = mutableListOf<AllowedModelRoute>()
                        while (list.size < count) {
                            list.addAll(groupRoutes)
                        }
                        list.take(count)
                    }
                    val aggregator = policy.defaultRoute?.takeIf { policy.isRouteAllowed(it) }
                        ?: groupRoutes.firstOrNull()
                        ?: fallbackEntry?.let { entryToRoute(it) }?.takeIf { policy.isRouteAllowed(it) }
                    return Pair(advisors, aggregator)
                }
            }
        }

        // 4. 白名单回退（若设置了白名单）
        val allowed = policy.allowedRoutes
        if (!allowed.isNullOrEmpty()) {
            val advisors = if (allowed.size >= count) {
                allowed.take(count)
            } else {
                val list = mutableListOf<AllowedModelRoute>()
                while (list.size < count) {
                    list.addAll(allowed)
                }
                list.take(count)
            }
            val aggregator = policy.defaultRoute?.takeIf { policy.isRouteAllowed(it) } ?: allowed.firstOrNull()
            return Pair(advisors, aggregator)
        }

        // 5. 兜底方案（仅在非白名单模式且未配置白名单时使用）
        val candidateRoutes = allEntries.map { entryToRoute(it) }
        val advisors = if (candidateRoutes.isNotEmpty()) {
            candidateRoutes.take(count)
        } else if (fallbackEntry != null) {
            listOf(entryToRoute(fallbackEntry))
        } else {
            emptyList()
        }
        val aggregator = policy.defaultRoute ?: advisors.firstOrNull() ?: fallbackEntry?.let { entryToRoute(it) }
        return Pair(advisors, aggregator)
    }

    /**
     * 核心调度解析方法：为委托 (Delegate) 或自由子代理解析并校验最终生效的模型路由
     */
    fun resolveChildRoute(
        requestedModel: String? = null,
        requestedGroup: String? = null,
        fallbackEntry: ModelEntry? = null,
    ): AllowedModelRoute {
        val currentPolicy = _config.value.modelRoutePolicy
        val allEntries = getAllAvailableCandidateEntries()
        val groups = getAllModelGroups()

        // 1. 指定了模型组的情况（显式传入或策略指定）
        val effectiveGroup = requestedGroup ?: currentPolicy.targetGroupId
        if (!effectiveGroup.isNullOrBlank()) {
            val group = groups.find {
                it.id.equals(effectiveGroup, ignoreCase = true) ||
                    it.name.equals(effectiveGroup, ignoreCase = true)
            }
            if (group != null && group.memberEntryIds.isNotEmpty()) {
                val groupEntries = allEntries.filter { it.id in group.memberEntryIds }
                if (groupEntries.isNotEmpty()) {
                    // 如果同时指定了模型名称，在 group 内筛选
                    if (!requestedModel.isNullOrBlank()) {
                        val matchedInGroup = groupEntries.find {
                            it.model.id.equals(requestedModel, ignoreCase = true) ||
                                it.model.displayName.equals(requestedModel, ignoreCase = true)
                        }
                        if (matchedInGroup != null) {
                            val route = entryToRoute(matchedInGroup)
                            currentPolicy.assertAllowed(route)
                            return route
                        }
                    }
                    // 否则取组内首个允许的
                    val firstAllowed = groupEntries.map { entryToRoute(it) }.firstOrNull { route ->
                        currentPolicy.isRouteAllowed(route)
                    }
                    if (firstAllowed != null) return firstAllowed
                }
            }
        }

        // 2. 指定了具体模型的情况
        if (!requestedModel.isNullOrBlank()) {
            val matchedEntry = allEntries.find {
                it.model.id.equals(requestedModel, ignoreCase = true) ||
                    it.model.displayName.equals(requestedModel, ignoreCase = true) ||
                    "${it.model.provider}/${it.model.id}".equals(requestedModel, ignoreCase = true)
            }
            if (matchedEntry != null) {
                val route = entryToRoute(matchedEntry)
                currentPolicy.assertAllowed(route)
                return route
            } else {
                val parts = requestedModel.split("/", limit = 2)
                val prov = if (parts.size == 2) parts[0] else ""
                val mod = if (parts.size == 2) parts[1] else requestedModel
                val route = AllowedModelRoute(providerId = prov, modelId = mod, displayName = requestedModel)
                currentPolicy.assertAllowed(route)
                return route
            }
        }

        // 3. 自由子代理调度（未指定）
        // 优先 1：设置的默认子代理模型（必须受白名单约束）
        currentPolicy.defaultRoute?.let { defRoute ->
            val exists = allEntries.any { it.model.id.equals(defRoute.modelId, ignoreCase = true) }
            if (exists && currentPolicy.isRouteAllowed(defRoute)) {
                return defRoute
            }
        }

        // 优先 2：若为自定义模式，取自定义首项
        if (currentPolicy.sourceMode == SubagentModelSourceMode.CUSTOM_ROUTES) {
            currentPolicy.customAdvisorRoutes?.firstOrNull()?.let {
                currentPolicy.assertAllowed(it)
                return it
            }
        }

        // 优先 3：白名单首项
        currentPolicy.allowedRoutes?.firstOrNull()?.let { return it }

        // 如果处于白名单模式，此时若无匹配项必须阻断
        if (currentPolicy.sourceMode == SubagentModelSourceMode.WHITELIST) {
            throw SubagentPolicyViolationException("当前为白名单模式，但没有可用的白名单模型，请在设置中配置。")
        }

        // 优先 4：主会话回落（受 policy 校验）
        if (fallbackEntry != null) {
            val route = entryToRoute(fallbackEntry)
            currentPolicy.assertAllowed(route)
            return route
        }

        // 终极保底：候选列表中任意首个通过校验的模型
        val firstAllowed = allEntries.map { entryToRoute(it) }.firstOrNull { route ->
            currentPolicy.isRouteAllowed(route)
        }
        if (firstAllowed != null) return firstAllowed

        val first = allEntries.firstOrNull()
            ?: throw IllegalStateException("没有可用于子代理的模型配置，请先在模型设置中添加并启用 Provider。")
        val finalRoute = entryToRoute(first)
        currentPolicy.assertAllowed(finalRoute)
        return finalRoute
    }

    companion object {
        private const val PREFS_NAME = "subagent_config_prefs"
        private const val KEY_MODE = "subagent_mode"
        private const val KEY_AGENT_COUNT_TYPE = "subagent_count_type"
        private const val KEY_AGENT_COUNT_VALUE = "subagent_count_value"
        private const val KEY_SOURCE_MODE = "subagent_source_mode"
        private const val KEY_DEFAULT_ROUTE = "subagent_default_route"
        private const val KEY_ALLOWED_ROUTES = "subagent_allowed_routes"
        private const val KEY_CUSTOM_ADVISORS = "subagent_custom_advisors"
        private const val KEY_TARGET_GROUP_ID = "subagent_target_group_id"
        private const val KEY_MAX_DEPTH = "subagent_max_depth"
        private const val KEY_TIMEOUT_SEC = "subagent_timeout_sec"
    }
}
