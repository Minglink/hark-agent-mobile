package com.openminis.app.ui.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.MemoryPressure
import com.openminis.app.sandbox.SandboxMetricsState
import com.openminis.app.ui.theme.ChatColors

/**
 * SandboxAmbientRibbon — 沙盒运行环境微型胶囊指示器
 *
 * 遵循 ui-craft (C:\Users\Administrator\Desktop\ui-craft-master) 规范：
 * - 紧凑尺寸 (30dp 高度，15dp 胶囊半圆角，10dp 水平内边距)
 * - “白卡蓝”材质：微磨砂白卡高光底 + 科技冰川蓝微光描边
 * - 核心数据双轨并列：彻底区分【机身存储 (Flash/ROM)】与【运行内存剩余及百分比 (RAM)】
 */
@Composable
fun SandboxAmbientRibbon(
    metrics: SandboxMetricsState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = ChatColors.isDark
    val cardBg = if (isDark) Color(0xFF131D2E).copy(alpha = 0.92f) else Color(0xFFF3F8FF)
    val cardBorder = if (isDark) Color(0xFF3B82F6).copy(alpha = 0.35f) else Color(0xFF2563EB).copy(alpha = 0.28f)
    val blueAccent = if (isDark) Color(0xFF60A5FA) else Color(0xFF0066EE)
    val textColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)

    // 格式化机身外部大存储可用大小
    val storageText = when {
        metrics.externalFreeGb >= 1.0 -> {
            val formatted = String.format(java.util.Locale.US, "%.1f", metrics.externalFreeGb)
            "${formatted}G"
        }
        metrics.externalFreeGb > 0.0 -> {
            "${(metrics.externalFreeGb * 1024).toInt()}M"
        }
        else -> "--"
    }

    // 格式化系统物理 RAM 剩余大小与百分比
    val ramText = when {
        metrics.systemMemoryFreeMb >= 1024 -> {
            val gb = metrics.systemMemoryFreeMb / 1024.0
            val formatted = String.format(java.util.Locale.US, "%.1f", gb)
            "${formatted}G"
        }
        metrics.systemMemoryFreeMb > 0 -> {
            "${metrics.systemMemoryFreeMb}M"
        }
        else -> "--"
    }

    val ramDisplay = if (metrics.systemMemoryFreePercent > 0) {
        "运余 $ramText(${metrics.systemMemoryFreePercent}%)"
    } else {
        "运余 $ramText"
    }

    Surface(
        shape = RoundedCornerShape(15.dp),
        color = cardBg,
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .border(0.5.dp, cardBorder, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 10.dp)
        ) {
            // 终端沙盒图标
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = blueAccent,
                modifier = Modifier.size(13.dp)
            )

            Spacer(modifier = Modifier.width(5.dp))

            // 1. 存储内存展示 (ROM 闪存大空间)
            Text(
                text = "存 $storageText",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            )

            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "·",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = ChatColors.secondaryText
                )
            )
            Spacer(modifier = Modifier.width(4.dp))

            // 2. 运行内存剩余及百分比
            Text(
                text = ramDisplay,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (metrics.memoryPressure >= MemoryPressure.HIGH) Color(0xFFF59E0B) else ChatColors.secondaryText
                )
            )

            // 3. 辅助指示符：挂载文件夹 / 后台服务 / 内存紧张警示
            if (metrics.mountedFolderCount > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "📁${metrics.mountedFolderCount}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = blueAccent
                    )
                )
            }

            if (metrics.runningServicesCount > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(blueAccent.copy(alpha = 0.5f), CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "⚡${metrics.runningServicesCount}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = blueAccent
                    )
                )
            } else if (metrics.memoryPressure >= MemoryPressure.HIGH) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(Color(0xFFEF4444), CircleShape)
                )
            }
        }
    }
}
