package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.openminis.app.ui.components.MinisOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.agent.subagent.SubagentRegistry
import com.openminis.app.agent.subagent.SubagentRole
import com.openminis.app.agent.subagent.SubagentState
import com.openminis.app.ui.components.MinisCompactButton
import com.openminis.app.ui.components.pressScaleEffect
import com.openminis.app.ui.theme.UiCraftTokens
import kotlinx.coroutines.launch

/**
 * 子代理沉浸式检查与人工干预抽屉 (Subagent Inspector Sheet)
 * 允许用户深入查看每个子代理的思考过程、工具调用与日志，并在运行中或完成后注入干预指令。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentInspectorSheet(
    record: SubagentRegistry.SubagentRecord,
    onDismiss: () -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val scope = rememberCoroutineScope()
    var interventionText by remember { mutableStateOf("") }
    var interventionSentNotice by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(UiCraftTokens.GrabberWidth)
                        .height(UiCraftTokens.GrabberHeight)
                        .background(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(
            topStart = UiCraftTokens.SheetCornerRadius,
            topEnd = UiCraftTokens.SheetCornerRadius,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 1. 顶部标题栏与关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(UiCraftTokens.CardCornerRadiusSmall))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = record.handle.route.displayName ?: record.handle.route.modelId,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val roleBadge = when (record.handle.role) {
                                SubagentRole.Advisor -> "顾问"
                                SubagentRole.Delegate -> "委托"
                                SubagentRole.Aggregator -> "聚合"
                            }
                            Text(
                                text = "[$roleBadge]",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = "提供商: ${record.handle.route.providerId.ifBlank { "默认" }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!record.childSessionId.isNullOrBlank() && onOpenSession != null) {
                        MinisCompactButton(
                            onClick = {
                                onDismiss()
                                onOpenSession(record.childSessionId)
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("完整对话", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .clip(CircleShape)
                            .pressScaleEffect(targetScale = 0.92f)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. 状态徽章与统计卡
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 状态徽标
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (record.state) {
                            SubagentState.RUNNING, SubagentState.STARTING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("执行中", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            SubagentState.SUCCEEDED -> {
                                Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF34C759), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("任务成功", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF34C759))
                            }
                            SubagentState.FAILED, SubagentState.CANCELLED, SubagentState.INTERRUPTED -> {
                                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (record.state == SubagentState.CANCELLED) "已取消" else "执行失败", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            }
                            else -> {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Gray))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("就绪中", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 消耗与指标
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tokens = record.result?.tokensUsed ?: 0
                        val cost = record.result?.estimatedCostUsd ?: 0.0
                        val duration = record.result?.durationSeconds ?: 0.0

                        if (tokens > 0) {
                            Text("$tokens tok", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (cost > 0.0) {
                            Text("≈ $${String.format("%.4f", cost)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (duration > 0.0) {
                            Text("${String.format("%.1f", duration)}s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 快速操作栏（打开完整会话 / 终止）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!record.childSessionId.isNullOrBlank() && onOpenSession != null) {
                    MinisOutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenSession(record.childSessionId)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("查看完整会话", fontSize = 13.sp)
                    }
                }

                if (!record.state.isTerminal) {
                    MinisOutlinedButton(
                        onClick = { SubagentRegistry.cancel(record.handle.id, "用户手动停止") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.StopCircle, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("停止子代理", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 内容与日志滚动区
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 340.dp)
                    .verticalScroll(scrollState),
            ) {
                // 目标卡片
                Text("任务目标与指令", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = record.handle.goal,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!record.handle.context.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "上下文背景: ${record.handle.context}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 结论或返回结果
                if (record.result != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("执行结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ) {
                        Text(
                            text = record.result.summary ?: "(无返回文本)",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 实时步骤与日志
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (!record.currentStep.isNullOrBlank()) "当前步骤: ${record.currentStep}" else "执行日志流",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (record.liveLogs.isEmpty()) {
                            Text(
                                text = if (record.state == SubagentState.RUNNING) "正在执行中，等待输出..." else "暂无更多详细日志",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            record.liveLogs.forEach { log ->
                                Text(
                                    text = log,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                    color = if (log.startsWith("【人工干预】")) MaterialTheme.colorScheme.primary
                                    else if (log.startsWith("错误")) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. 干预反馈提醒
            AnimatedVisibility(visible = interventionSentNotice != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF34C759).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF34C759), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(interventionSentNotice ?: "", fontSize = 12.sp, color = Color(0xFF1E7E34), fontWeight = FontWeight.Medium)
                }
            }

            // 6. 人工纠偏干预输入栏 (Human-in-the-Loop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = interventionText,
                    onValueChange = { interventionText = it },
                    placeholder = {
                        Text("发送纠偏指令 (如: 只看特定目录/改变策略)...", fontSize = 12.sp)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (interventionText.isNotBlank()) {
                                SubagentRegistry.sendIntervention(record.handle.id, interventionText.trim())
                                interventionSentNotice = "已向子代理发送干预指令: '${interventionText.trim()}'"
                                interventionText = ""
                            }
                        }
                    ),
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (interventionText.isNotBlank()) {
                            SubagentRegistry.sendIntervention(record.handle.id, interventionText.trim())
                            interventionSentNotice = "已向子代理发送干预指令: '${interventionText.trim()}'"
                            interventionText = ""
                        }
                    },
                    enabled = interventionText.isNotBlank(),
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (interventionText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送纠偏指令",
                        tint = if (interventionText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
