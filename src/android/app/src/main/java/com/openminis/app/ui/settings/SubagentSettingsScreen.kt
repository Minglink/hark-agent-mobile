package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.subagent.AgentCount
import com.openminis.app.agent.subagent.AllowedModelRoute
import com.openminis.app.agent.subagent.SubagentModelSourceMode
import com.openminis.app.agent.subagent.TeamworkMode
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.SubagentRepository

@Composable
fun SubagentSettingsScreen(
    onBack: () -> Unit,
    subagentRepository: SubagentRepository,
) {
    val config by subagentRepository.config.collectAsState()
    val policy = config.modelRoutePolicy
    val availableEntries = remember { subagentRepository.getAllAvailableCandidateEntries() }
    val availableGroups = remember { subagentRepository.getAllModelGroups() }
    val groupedEntries = remember(availableEntries, availableGroups) { subagentRepository.getGroupedCandidateEntries() }
    val ungroupedEntries = remember(availableEntries, availableGroups) { subagentRepository.getUngroupedCandidateEntries() }

    SettingsScaffold(
        title = "子代理协同设置 (Subagent Teamwork)",
        onBack = onBack,
    ) {
        // ── 1. 协作模式设置 ──────────────────────────────────────────────────
        SettingsSection(
            header = "团队协作模式",
            footer = "选择在通过 /teamwork 或调用子代理时采用的默认编排策略。",
        ) {
            TeamworkMode.values().forEach { mode ->
                val isSelected = config.mode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { subagentRepository.setMode(mode) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { subagentRepository.setMode(mode) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.label,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = mode.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // ── 2. 子代理模型来源模式选择 ──────────────────────────────────────────
        SettingsSection(
            header = "子代理调度来源模式 (Model Selection Strategy)",
            footer = "推荐使用【按模型组调度】，子代理将自动在该组内的不同专业模型中分配；也可以自定义指定各个顾问模型或在分类白名单中筛选。",
        ) {
            SubagentModelSourceMode.values().forEach { mode ->
                val isSelected = policy.sourceMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { subagentRepository.setSourceMode(mode) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { subagentRepository.setSourceMode(mode) },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.label,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = mode.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // ── 3. 来源模式专属配置区 ────────────────────────────────────────────
        when (policy.sourceMode) {
            SubagentModelSourceMode.MODEL_GROUP -> {
                // 绑定模型组模式
                SettingsSection(
                    header = "绑定目标模型组 (Target Model Group)",
                    footer = "选定模型组后，子代理（特别是 MoA 混合专家）将自动在组内的各个模型间分配。例如组内有 Claude、DeepSeek、GPT，各顾问将分别使用不同模型进行多视角分析。",
                ) {
                    var groupDropdown by remember { mutableStateOf(false) }
                    val currentGroupId = policy.targetGroupId
                    val currentGroup = availableGroups.find { it.id == currentGroupId }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupDropdown = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "当前绑定的模型组", fontSize = 15.sp)
                            Text(
                                text = currentGroup?.let { "${it.name} (${it.memberEntryIds.size} 个模型)" }
                                    ?: "未指定 (点击选择模型组)",
                                fontSize = 13.sp,
                                color = if (currentGroup != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = if (currentGroup != null) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }

                        DropdownMenu(
                            expanded = groupDropdown,
                            onDismissRequest = { groupDropdown = false },
                        ) {
                            if (availableGroups.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无可用模型组，请在【模型组管理】中创建") },
                                    onClick = { groupDropdown = false },
                                )
                            } else {
                                availableGroups.forEach { group ->
                                    val isCurr = group.id == currentGroupId
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(group.name, fontWeight = FontWeight.Medium)
                                                Text("${group.memberEntryIds.size} 个成员模型", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            subagentRepository.setTargetGroupId(group.id)
                                            groupDropdown = false
                                        },
                                        trailingIcon = if (isCurr) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }

                    // 展开展示选定组内的模型列表预览
                    if (currentGroup != null) {
                        val groupMembers = availableEntries.filter { it.id in currentGroup.memberEntryIds }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "组内子代理调度池预览 (${groupMembers.size} 个模型):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (groupMembers.isEmpty()) {
                                Text(
                                    text = "提示：此组内尚未添加已启用的模型，请前往【模型组】添加。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                groupMembers.forEachIndexed { idx, entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.SmartToy,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "顾问 ${idx + 1}: ${entry.model.displayName}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "(${entry.model.provider})",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SubagentModelSourceMode.CUSTOM_ROUTES -> {
                // 自定义指定顾问模型
                SettingsSection(
                    header = "自定义固定顾问模型 (Custom Assigned Models)",
                    footer = "为 MoA 协同的各个顾问专家显式绑定具体的模型，精确指定由哪几个特定模型进行协同思考。",
                ) {
                    val customList = policy.customAdvisorRoutes ?: emptyList()
                    val count = config.agentCount.resolvedCount(3)

                    for (slotIdx in 0 until count) {
                        var dropdownOpen by remember { mutableStateOf(false) }
                        val currentSlotRoute = customList.getOrNull(slotIdx)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dropdownOpen = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "顾问 ${slotIdx + 1} 模型",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = currentSlotRoute?.displayName ?: currentSlotRoute?.modelId ?: "点击选择指定模型",
                                    fontSize = 13.sp,
                                    color = if (currentSlotRoute != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            DropdownMenu(
                                expanded = dropdownOpen,
                                onDismissRequest = { dropdownOpen = false },
                            ) {
                                availableEntries.forEach { entry ->
                                    val route = subagentRepository.entryToRoute(entry)
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(entry.model.displayName, fontWeight = FontWeight.Medium)
                                                Text("${entry.model.provider} · ${entry.model.id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            val newList = customList.toMutableList()
                                            while (newList.size <= slotIdx) {
                                                newList.add(route)
                                            }
                                            newList[slotIdx] = route
                                            subagentRepository.setCustomAdvisorRoutes(newList)
                                            dropdownOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SubagentModelSourceMode.WHITELIST -> {
                // 分类白名单模式
            }
        }

        // ── 4. 模型组分类白名单卡片展示 ────────────────────────────────────────
        SettingsSection(
            header = "模型白名单与分类层级 (Categorized Whitelist)",
            footer = "按【模型组】与【未分组服务商】分类展示。可对整组一键全选或清空，只有勾选的模型允许被子代理调度。",
        ) {
            val whitelist = policy.allowedRoutes

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        subagentRepository.setAllowedRoutes(availableEntries.map { subagentRepository.entryToRoute(it) })
                    }
                ) {
                    Text("全选所有", fontSize = 13.sp)
                }
                TextButton(
                    onClick = {
                        subagentRepository.setAllowedRoutes(emptyList())
                    }
                ) {
                    Text("清空所有", fontSize = 13.sp)
                }
            }

            if (availableEntries.isEmpty()) {
                Text(
                    text = "暂无可用的模型，请先在【服务商管理】中配置并启用 Provider。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                // 1. 各模型组卡片
                groupedEntries.forEach { (group, memberEntries) ->
                    ModelGroupWhitelistCard(
                        group = group,
                        memberEntries = memberEntries,
                        whitelist = whitelist,
                        subagentRepository = subagentRepository,
                        allCandidates = availableEntries,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 2. 未分组模型卡片（按服务商）
                if (ungroupedEntries.isNotEmpty()) {
                    ungroupedEntries.forEach { (providerName, entries) ->
                        ProviderUngroupedWhitelistCard(
                            providerName = providerName,
                            entries = entries,
                            whitelist = whitelist,
                            subagentRepository = subagentRepository,
                            allCandidates = availableEntries,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // ── 5. 默认调度与聚合器模型 ──────────────────────────────────────────
        SettingsSection(
            header = "默认调度模型 (Default / Aggregator Model)",
            footer = "未明确指定模型或单代理调度时使用的保底模型；在 MoA 中此模型也将担任综合提炼方案的聚合器 (Aggregator)。",
        ) {
            var expandedDropdown by remember { mutableStateOf(false) }
            val currentDefault = policy.defaultRoute

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedDropdown = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "默认调度 / 聚合模型",
                        fontSize = 15.sp,
                    )
                    Text(
                        text = currentDefault?.displayName ?: "跟随主会话模型 (默认)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                DropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("跟随主会话模型 (不固定)") },
                        onClick = {
                            subagentRepository.setDefaultRoute(null)
                            expandedDropdown = false
                        },
                        trailingIcon = if (currentDefault == null) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                    availableEntries.forEach { entry ->
                        val route = subagentRepository.entryToRoute(entry)
                        val isCurr = currentDefault?.modelId == route.modelId &&
                            (currentDefault.providerId.isEmpty() || currentDefault.providerId == route.providerId)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(entry.model.displayName, fontWeight = FontWeight.Medium)
                                    Text("${entry.model.provider} · ${entry.model.id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                subagentRepository.setDefaultRoute(route)
                                expandedDropdown = false
                            },
                            trailingIcon = if (isCurr) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                        )
                    }
                }
            }
        }

        // ── 6. 并发数量与参数 ────────────────────────────────────────────────
        SettingsSection(
            header = "子代理并发数量",
            footer = "设置团队模式下启动的子代理/顾问专家数量，或设为“自动”弹性决定。",
        ) {
            val count = config.agentCount
            val counts = listOf(2, 3, 4, 6)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("子代理数", fontSize = 15.sp)

                Row {
                    counts.forEach { n ->
                        val isSel = count is AgentCount.Fixed && count.count == n
                        TextButton(
                            onClick = { subagentRepository.setAgentCount(AgentCount.Fixed(n)) },
                        ) {
                            Text(
                                text = "$n 个",
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val isAuto = count is AgentCount.Auto
                    TextButton(
                        onClick = { subagentRepository.setAgentCount(AgentCount.Auto) },
                    ) {
                        Text(
                            text = "自动",
                            fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Normal,
                            color = if (isAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 单个模型组分类折叠白名单卡片
 */
@Composable
private fun ModelGroupWhitelistCard(
    group: ModelGroup,
    memberEntries: List<ModelEntry>,
    whitelist: List<AllowedModelRoute>?,
    subagentRepository: SubagentRepository,
    allCandidates: List<ModelEntry>,
) {
    var expanded by remember { mutableStateOf(false) }

    val groupRoutes = remember(memberEntries) { memberEntries.map { subagentRepository.entryToRoute(it) } }
    val effectiveWhitelist = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }

    val checkedCount = groupRoutes.count { route ->
        effectiveWhitelist.any { it.modelId.equals(route.modelId, ignoreCase = true) }
    }
    val allChecked = checkedCount == groupRoutes.size && groupRoutes.isNotEmpty()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 卡片头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.GroupWork,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = group.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "已选 $checkedCount / ${groupRoutes.size} 个模型",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                            val updated = if (allChecked) {
                                current.filterNot { curr -> groupRoutes.any { it.modelId.equals(curr.modelId, ignoreCase = true) } }
                            } else {
                                val toAdd = groupRoutes.filterNot { gr -> current.any { it.modelId.equals(gr.modelId, ignoreCase = true) } }
                                current + toAdd
                            }
                            subagentRepository.setAllowedRoutes(updated)
                        }
                    ) {
                        Text(if (allChecked) "清空本组" else "全选本组", fontSize = 12.sp)
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开后的具体模型列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    groupRoutes.forEach { route ->
                        val isChecked = effectiveWhitelist.any { it.modelId.equals(route.modelId, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                                    val updated = if (isChecked) {
                                        current.filterNot { it.modelId.equals(route.modelId, ignoreCase = true) }
                                    } else {
                                        current + route
                                    }
                                    subagentRepository.setAllowedRoutes(updated)
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                                    val updated = if (!checked) {
                                        current.filterNot { it.modelId.equals(route.modelId, ignoreCase = true) }
                                    } else {
                                        current + route
                                    }
                                    subagentRepository.setAllowedRoutes(updated)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = route.displayName ?: route.modelId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${route.providerId} · ${route.modelId}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 未分组模型折叠白名单卡片（按服务商分类）
 */
@Composable
private fun ProviderUngroupedWhitelistCard(
    providerName: String,
    entries: List<ModelEntry>,
    whitelist: List<AllowedModelRoute>?,
    subagentRepository: SubagentRepository,
    allCandidates: List<ModelEntry>,
) {
    var expanded by remember { mutableStateOf(false) }

    val routes = remember(entries) { entries.map { subagentRepository.entryToRoute(it) } }
    val effectiveWhitelist = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }

    val checkedCount = routes.count { route ->
        effectiveWhitelist.any { it.modelId.equals(route.modelId, ignoreCase = true) }
    }
    val allChecked = checkedCount == routes.size && routes.isNotEmpty()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 卡片头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "服务商: $providerName",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "未加入模型组 · 已选 $checkedCount / ${routes.size} 个模型",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                            val updated = if (allChecked) {
                                current.filterNot { curr -> routes.any { it.modelId.equals(curr.modelId, ignoreCase = true) } }
                            } else {
                                val toAdd = routes.filterNot { gr -> current.any { it.modelId.equals(gr.modelId, ignoreCase = true) } }
                                current + toAdd
                            }
                            subagentRepository.setAllowedRoutes(updated)
                        }
                    ) {
                        Text(if (allChecked) "清空" else "全选", fontSize = 12.sp)
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开后的具体模型列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    routes.forEach { route ->
                        val isChecked = effectiveWhitelist.any { it.modelId.equals(route.modelId, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                                    val updated = if (isChecked) {
                                        current.filterNot { it.modelId.equals(route.modelId, ignoreCase = true) }
                                    } else {
                                        current + route
                                    }
                                    subagentRepository.setAllowedRoutes(updated)
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val current = whitelist ?: allCandidates.map { subagentRepository.entryToRoute(it) }
                                    val updated = if (!checked) {
                                        current.filterNot { it.modelId.equals(route.modelId, ignoreCase = true) }
                                    } else {
                                        current + route
                                    }
                                    subagentRepository.setAllowedRoutes(updated)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = route.displayName ?: route.modelId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${route.providerId} · ${route.modelId}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
