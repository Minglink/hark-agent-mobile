package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.goal.GoalStatus
import com.openminis.app.goal.SessionGoal
import com.openminis.app.ui.theme.ChatColors

/**
 * GoalContextPillBar — 会话当前目标与进度指示胶囊条
 *
 * 遵循 ui-craft 设计规范：
 * - Rule 13: 30dp 紧凑胶囊高度，12dp 水平内边距
 * - Rule 4 & 10: 字体层级排版，清晰展示当前里程碑
 * - Rule 7 & 15: 色彩作为信号（完成绿、推进蓝、空闲灰阶弱化）
 */
@Composable
fun GoalContextPillBar(
    goal: SessionGoal?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (goal != null) ChatColors.toolCapsuleBg.copy(alpha = 0.88f) else ChatColors.inputBg.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 0.5.dp,
                    color = if (goal != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else ChatColors.separator.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable(onClick = onClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .height(30.dp)
                    .padding(horizontal = 12.dp)
            ) {
                if (goal == null) {
                    // 未设定目标时的轻量提示
                    Icon(
                        imageVector = Icons.Outlined.ChecklistRtl,
                        contentDescription = null,
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "设定会话目标",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ChatColors.secondaryText
                        )
                    )
                } else {
                    // 已激活目标状态
                    val isCompleted = goal.status == GoalStatus.COMPLETED
                    val iconTint = if (isCompleted) Color(0xFF34C759) else MaterialTheme.colorScheme.primary

                    Icon(
                        imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Outlined.ChecklistRtl,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(14.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = goal.goalText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = ChatColors.primaryText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (goal.totalMilestones > 0) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = ChatColors.tertiaryText,
                                fontSize = 11.sp
                            )
                        )
                        Text(
                            text = "${goal.completedMilestones}/${goal.totalMilestones}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isCompleted) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
