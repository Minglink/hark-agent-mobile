package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.subagent.SubagentRegistry
import com.openminis.app.agent.subagent.SubagentState

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key

import com.openminis.app.ui.components.pressScaleEffect
import com.openminis.app.ui.theme.UiCraftTokens
import com.openminis.app.ui.theme.withTabularNumbers
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * 团队协作与子代理实时状态栏（支持展开查看费用与子任务明细，点击子任务弹出 Inspector 与干预抽屉）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SubagentStatusBar(
    sessionId: String,
    modifier: Modifier = Modifier,
    onNavigateToSession: ((String) -> Unit)? = null,
) {
    val records by SubagentRegistry.observeSessionSubagents(sessionId).collectAsState()
    if (records.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var inspectingRecordId by remember { mutableStateOf<String?>(null) }

    // 使用 derivedStateOf 消除高频重组帧抖动
    val runningCount by remember(records) {
        derivedStateOf { records.count { it.state == SubagentState.RUNNING || it.state == SubagentState.STARTING } }
    }
    val totalCost by remember(records) {
        derivedStateOf { records.sumOf { it.result?.estimatedCostUsd ?: 0.0 } }
    }
    val totalTokens by remember(records) {
        derivedStateOf { records.sumOf { it.result?.tokensUsed ?: 0 } }
    }

    val selectedRecord = remember(records, inspectingRecordId) {
        inspectingRecordId?.let { id -> records.find { it.handle.id == id } }
    }

    selectedRecord?.let { rec ->
        SubagentInspectorSheet(
            record = rec,
            onDismiss = { inspectingRecordId = null },
            onOpenSession = onNavigateToSession,
        )
    }

    val topInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = com.openminis.app.ui.theme.ObsidianTokens.ScreenGutter, vertical = 4.dp),
        shape = RoundedCornerShape(com.openminis.app.ui.theme.ObsidianTokens.CardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 顶层汇总栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScaleEffect(targetScale = 0.98f, interactionSource = topInteraction)
                    .clickable(
                        interactionSource = topInteraction,
                        indication = null,
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    if (runningCount > 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.GroupWork,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (runningCount > 0) "子代理协作中 ($runningCount/${records.size} 运行)"
                        else "团队协作完成 (${records.size} 个子代理)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (totalCost > 0.0 || totalTokens > 0) {
                        Text(
                            text = "≈ $${String.format("%.4f", totalCost)}",
                            fontSize = 12.sp,
                            style = LocalTextStyle.current.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开后的各子代理明细列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    records.forEach { record ->
                        key(record.handle.id) {
                            SubagentDetailItem(
                                record = record,
                                onClick = { inspectingRecordId = record.handle.id },
                                onOpenSession = onNavigateToSession,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentDetailItem(
    record: SubagentRegistry.SubagentRecord,
    onClick: () -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
) {
    val itemInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScaleEffect(targetScale = 0.97f, interactionSource = itemInteraction)
            .clip(RoundedCornerShape(UiCraftTokens.CardCornerRadiusSmall))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(UiCraftTokens.CardCornerRadiusSmall))
            .clickable(
                interactionSource = itemInteraction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            when (record.state) {
                SubagentState.RUNNING, SubagentState.STARTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
                SubagentState.SUCCEEDED -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF34C759),
                    )
                }
                SubagentState.FAILED, SubagentState.CANCELLED, SubagentState.INTERRUPTED -> {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Gray),
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.handle.route.displayName ?: record.handle.route.modelId,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "[${record.handle.role.name}]",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = record.currentStep ?: record.result?.summary ?: record.handle.goal,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 状态、消耗信息与快捷操作
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            record.result?.let { res ->
                Text(
                    text = "${res.tokensUsed} tok",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(4.dp))
                if ((res.estimatedCostUsd ?: 0.0) > 0.0) {
                    Text(
                        text = "$${String.format("%.4f", res.estimatedCostUsd)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            if (!record.childSessionId.isNullOrBlank() && onOpenSession != null) {
                IconButton(
                    onClick = { onOpenSession(record.childSessionId) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "进入会话",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (!record.state.isTerminal) {
                IconButton(
                    onClick = { SubagentRegistry.cancel(record.handle.id, "用户手动停止") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
