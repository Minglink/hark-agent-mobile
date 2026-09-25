package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.goal.GoalManager
import com.openminis.app.goal.GoalMilestone
import com.openminis.app.goal.GoalStatus
import com.openminis.app.goal.MilestoneStatus
import com.openminis.app.goal.SessionGoal
import com.openminis.app.ui.theme.ChatColors

/**
 * GoalDetailSheet — 目标设定与里程碑执行看板抽屉 (窄屏自适应与排版加固版)
 *
 * 遵循 ui-craft (C:\Users\Administrator\Desktop\ui-craft-master) 规范：
 * - Rule 8 (Sheets are the app's second surface): 标准 36x4 抓手、× 关闭按键、纵向安全滚动
 * - Rule 13 (Buttons and chips are compact): 36dp 矮按钮，横向平滑滚动芯片，窄屏绝不超宽溢出
 * - Rule 9 (Empty states): 极简指引
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailSheet(
    goal: SessionGoal?,
    onDismissRequest: () -> Unit,
    onSetGoal: (String) -> Unit,
    onToggleMilestone: (milestoneId: String, currentStatus: MilestoneStatus) -> Unit,
    onClearGoal: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isEditing by remember { mutableStateOf(goal == null) }
    var inputText by remember { mutableStateOf(goal?.goalText ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = ChatColors.secondaryBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(ChatColors.separator, CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // 头部标题与动作 (ui-craft Rule 8)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChecklistRtl,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEditing) "设定会话目标" else "当前会话目标",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = ChatColors.primaryText
                        )
                    )
                    Text(
                        text = "智能体围绕该目标自主分步拆解与推进",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = ChatColors.secondaryText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!isEditing && goal != null) {
                    IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑目标",
                            tint = ChatColors.secondaryText,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                IconButton(onClick = onDismissRequest, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isEditing) {
                // 编辑/新建目标输入框
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("例如：构建并部署 Python 本地后台服务，确保外部可用...", fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 130.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = ChatColors.separator,
                        focusedContainerColor = ChatColors.inputBg,
                        unfocusedContainerColor = ChatColors.inputBg,
                    ),
                    maxLines = 4,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 快捷目标预设 (横向平滑横滚，窄屏绝不超宽溢出挤压)
                Text(
                    text = "快速预设：",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = ChatColors.tertiaryText)
                )
                Spacer(modifier = Modifier.height(6.dp))

                val presets = listOf(
                    "启动后台持续构建与服务",
                    "分析项目代码并进行重构",
                    "排查并修复沙盒运行错误",
                    "编写测试用例并验证"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ChatColors.toolCapsuleBg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(0.5.dp, ChatColors.separator.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .clickable { inputText = preset }
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    color = ChatColors.secondaryText
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (goal != null) {
                        TextButton(
                            onClick = { isEditing = false },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("取消", color = ChatColors.secondaryText, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSetGoal(inputText.trim())
                                isEditing = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("保存并推进目标", fontSize = 12.sp)
                    }
                }
            } else if (goal != null) {
                // 展示已设定目标与里程碑步骤
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ChatColors.toolCapsuleBg.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, ChatColors.separator.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = goal.goalText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = ChatColors.primaryText
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "状态: ",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = ChatColors.tertiaryText)
                            )
                            val statusColor = when (goal.status) {
                                GoalStatus.COMPLETED -> Color(0xFF34C759)
                                GoalStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                                else -> ChatColors.secondaryText
                            }
                            Text(
                                text = when (goal.status) {
                                    GoalStatus.COMPLETED -> "已全部完成"
                                    GoalStatus.IN_PROGRESS -> "推进执行中"
                                    GoalStatus.PAUSED -> "已暂停"
                                    else -> goal.status.name
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = statusColor
                                )
                            )
                            if (goal.totalMilestones > 0) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "进度: ${goal.completedMilestones}/${goal.totalMilestones} 步",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = ChatColors.secondaryText)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 里程碑步骤列表
                Text(
                    text = "执行计划与里程碑：",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = ChatColors.primaryText
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (goal.milestones.isEmpty()) {
                    Text(
                        text = "暂无细分步骤。当 Agent 执行拆解时将自动生成步骤树，您也可直接向 Agent 发送执行指令。",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ChatColors.tertiaryText,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        goal.milestones.forEachIndexed { index, milestone ->
                            val isDone = milestone.status == MilestoneStatus.COMPLETED
                            val isRunning = milestone.status == MilestoneStatus.IN_PROGRESS

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ChatColors.toolCapsuleBg.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val next = if (isDone) MilestoneStatus.PENDING else MilestoneStatus.COMPLETED
                                        onToggleMilestone(milestone.id, next)
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isDone) Color(0xFF34C759) else if (isRunning) MaterialTheme.colorScheme.primary else ChatColors.tertiaryText,
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${index + 1}. ${milestone.title}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.sp,
                                                fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal,
                                                textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                                                color = if (isDone) ChatColors.secondaryText else ChatColors.primaryText
                                            )
                                        )
                                        if (milestone.detail.isNotBlank()) {
                                            Text(
                                                text = milestone.detail,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    color = ChatColors.tertiaryText
                                                ),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 底部清理/完成按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onClearGoal()
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清除当前目标", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = ChatColors.inputBg),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("完成", color = ChatColors.primaryText, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
