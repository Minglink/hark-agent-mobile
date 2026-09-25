package com.openminis.app.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Extension
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.theme.ChatColors

/**
 * SkillPickerSheet — 精准技能选择与激活抽屉
 *
 * 遵循 ui-craft (C:\Users\Administrator\Desktop\ui-craft-master) 规范：
 * - Rule 8 (Sheets are the app's second surface): 36×4dp 抓手，17sp/700 居中或左侧标题，× 关闭按钮
 * - Rule 4 (Rows are 44–56 pt and carry three things at most): 52dp 紧凑行，24dp 图标 + 15/600 标题 + 12sp 灰色副标
 * - Rule 10 (Type does the hierarchy, not boxes): 排版自然分层
 * - Rule 13 (Compact controls): 紧凑内边距与友好的 44dp 触控区域
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerSheet(
    skills: List<SkillRepository.Skill>,
    activeSkillId: String?,
    onDismissRequest: () -> Unit,
    onSelectSkill: (SkillRepository.Skill) -> Unit,
    onClearSkill: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchKeyword by remember { mutableStateOf("") }

    val filtered = remember(skills, searchKeyword) {
        val kw = searchKeyword.trim().lowercase()
        if (kw.isEmpty()) skills else skills.filter {
            it.name.lowercase().contains(kw) || it.description.lowercase().contains(kw)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = ChatColors.secondaryBg,
        dragHandle = {
            // ui-craft Rule 8: 36×4 抓手
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
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // 头部：标题与关闭按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "调用指定技能 (/skill)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = ChatColors.primaryText
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (activeSkillId != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClearSkill)
                    ) {
                        Text(
                            text = "清除激活",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 搜索过滤框
            OutlinedTextField(
                value = searchKeyword,
                onValueChange = { searchKeyword = it },
                placeholder = {
                    Text(
                        "搜索技能名称或描述...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ChatColors.tertiaryText,
                            fontSize = 13.sp
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = ChatColors.tertiaryText,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = ChatColors.separator.copy(alpha = 0.5f),
                    focusedContainerColor = ChatColors.inputBg,
                    unfocusedContainerColor = ChatColors.inputBg,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 技能列表
            if (filtered.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Text(
                        text = if (skills.isEmpty()) "尚未安装任何技能，可在设置中导入" else "未匹配到相关技能",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ChatColors.secondaryText,
                            fontSize = 13.sp
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.id }) { skill ->
                        val isCurrentActive = skill.id == activeSkillId || skill.name.equals(activeSkillId, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrentActive) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                ChatColors.toolCapsuleBg.copy(alpha = 0.4f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isCurrentActive) onClearSkill() else onSelectSkill(skill)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            if (isCurrentActive) MaterialTheme.colorScheme.primary else ChatColors.secondaryBg,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Extension,
                                        contentDescription = null,
                                        tint = if (isCurrentActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = skill.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = ChatColors.primaryText
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrentActive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "已激活",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }

                                    if (skill.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = skill.description.trim(),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = ChatColors.secondaryText,
                                                fontSize = 12.sp,
                                                lineHeight = 15.sp
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
        }
    }
}

/**
 * TargetedSkillPillBar — 激活的专属技能指示胶囊条
 *
 * 遵循 ui-craft 设计规范：
 * - Rule 13: 30dp 紧凑胶囊高度，10dp 起始内边距
 * - Rule 15: 色彩指示激活状态，微弱 0.5dp 描边
 */
@Composable
fun TargetedSkillPillBar(
    skillName: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
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
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable(onClick = onClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .height(30.dp)
                    .padding(start = 10.dp, end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "专属技能: $skillName",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = ChatColors.primaryText
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除技能",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

