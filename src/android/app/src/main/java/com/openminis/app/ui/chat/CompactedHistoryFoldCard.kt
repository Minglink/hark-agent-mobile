package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * Modern foldable card for compacted conversation history.
 * Replaces the endless list of grayed-out messages with an accordion-style
 * card that collapses older turns into a tidy summary block.
 */
@Composable
fun CompactedHistoryFoldCard(
    compactedCount: Int,
    summary: String,
    isFolded: Boolean,
    onToggleFold: () -> Unit,
    onViewSummary: () -> Unit,
    onRevert: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ChatColors.secondaryBg)
            .border(0.5.dp, Color(0xFFAF52DE).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header Row: Icon + Fold title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Icon(
                    imageVector = Icons.Default.CloseFullscreen,
                    contentDescription = null,
                    tint = Color(0xFFAF52DE),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "已压缩折叠 $compactedCount 条历史对话",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Quick toggle button on right edge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleFold)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (isFolded) "展开历史" else "收起历史",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFAF52DE),
                )
                Icon(
                    imageVector = if (isFolded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color(0xFFAF52DE),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Summary Preview Excerpt (if present)
        if (summary.isNotBlank()) {
            val preview = summary
                .lines()
                .filterNot { it.startsWith("#") || it.isBlank() }
                .take(2)
                .joinToString(" ")
                .trim()

            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    fontSize = 11.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                )
            }
        }

        // Footer Actions: View Full Summary & Revert
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (summary.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onViewSummary)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "查看完整总结",
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "完整交接摘要",
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (onRevert != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRevert)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "撤销压缩",
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "撤销压缩",
                        fontSize = 11.sp,
                        color = ChatColors.tertiaryText,
                    )
                }
            }
        }
    }
}
