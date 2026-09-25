package com.openminis.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.ContextUsageState
import com.openminis.app.ui.theme.ChatColors

@Composable
fun ContextUsageIndicator(
    state: ContextUsageState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    val purpleAccent = Color(0xFFAF52DE) // iOS Purple / AI Context Purple
    val dotColor = when {
        state.isCompacting -> purpleAccent
        state.level == ContextUsageState.Level.CRITICAL -> Color(0xFFFF3B30) // Red
        state.level == ContextUsageState.Level.WARNING -> Color(0xFFFF9500)  // Amber
        else -> purpleAccent                                                 // Purple for normal!
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val effectiveAlpha = if (state.isCompacting || state.level == ContextUsageState.Level.CRITICAL) {
        pulseAlpha
    } else 1.0f

    val isPurple = state.isCompacting || state.level == ContextUsageState.Level.NORMAL
    val isDark = ChatColors.isDark
    val containerBg = when {
        state.isCompacting -> purpleAccent.copy(alpha = 0.20f)
        isPurple -> if (isDark) purpleAccent.copy(alpha = 0.14f) else Color(0xFFFBF7FF)
        else -> ChatColors.secondaryBg
    }
    val containerBorder = when {
        state.isCompacting -> purpleAccent.copy(alpha = 0.50f)
        isPurple -> if (isDark) purpleAccent.copy(alpha = 0.38f) else Color(0xFF9333EA).copy(alpha = 0.30f)
        else -> ChatColors.separator
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .border(0.5.dp, containerBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .alpha(effectiveAlpha)
                .background(dotColor, CircleShape),
        )

        val displayText = if (state.isCompacting) {
            "压缩中…"
        } else if (compact) {
            "${state.percentage}%"
        } else {
            val usedK = if (state.usedTokens >= 1000) "${state.usedTokens / 1000}k" else "${state.usedTokens}"
            val windowK = "${state.windowTokens / 1000}k"
            "$usedK / $windowK (${state.percentage}%)"
        }

        Text(
            text = displayText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                state.isCompacting -> purpleAccent
                state.level == ContextUsageState.Level.CRITICAL -> dotColor
                state.level == ContextUsageState.Level.WARNING -> dotColor
                else -> if (isDark) Color(0xFFD8B4FE) else Color(0xFF7E22CE)
            },
        )
    }
}

@Composable
fun ContextDetailSheet(
    state: ContextUsageState,
    onDismiss: () -> Unit,
    onCompactNow: () -> Unit,
) {
    var autoCompactEnabled by remember { mutableStateOf(AutoCompactPrefs.isEnabled()) }

    StandardChatSheet(
        title = "上下文容量与压缩管理",
        onDismiss = onDismiss,
        heightFraction = 0.55f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Visual Progress Meter
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "当前用量: ${state.usedTokens} tokens",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.primaryText,
                    )
                    Text(
                        text = "${state.percentage}% / ${state.windowTokens / 1000}k",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (state.level) {
                            ContextUsageState.Level.NORMAL -> Color(0xFF34C759)
                            ContextUsageState.Level.WARNING -> Color(0xFFFF9500)
                            ContextUsageState.Level.CRITICAL -> Color(0xFFFF3B30)
                        },
                    )
                }

                LinearProgressIndicator(
                    progress = { state.ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when (state.level) {
                        ContextUsageState.Level.NORMAL -> Color(0xFFAF52DE)
                        ContextUsageState.Level.WARNING -> Color(0xFFFF9500)
                        ContextUsageState.Level.CRITICAL -> Color(0xFFFF3B30)
                    },
                    trackColor = ChatColors.secondaryBg,
                )

                Text(
                    text = "剩余可用: ~${state.remainingTokens} tokens",
                    fontSize = 11.sp,
                    color = ChatColors.tertiaryText,
                )
            }

            // Setting Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ChatColors.secondaryBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动压缩保护 (Auto-compact)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                    Text(
                        text = "当上下文达到 80% 时自动提炼摘要并折叠旧历史，杜绝 400 崩溃",
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = autoCompactEnabled,
                    onCheckedChange = { checked ->
                        autoCompactEnabled = checked
                        AutoCompactPrefs.setEnabled(checked)
                    },
                )
            }

            // Micro-compaction info badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ChatColors.secondaryBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color(0xFFAF52DE),
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = "常态微压缩防御",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.primaryText,
                    )
                    Text(
                        text = "历史思考链与超长工具输出已自动瘦身置换，严格保护用户原始意图",
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Manual compact button
            Button(
                onClick = {
                    onDismiss()
                    onCompactNow()
                },
                enabled = !state.isCompacting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007AFF),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Compress,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (state.isCompacting) "正在压缩中…" else "立即执行上下文压缩 (/compact)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
