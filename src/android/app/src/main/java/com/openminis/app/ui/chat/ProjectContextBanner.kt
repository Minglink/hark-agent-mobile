package com.openminis.app.ui.chat

import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ProjectEntity
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.launch

/**
 * ProjectContextPillBar — 会话项目与工作目录指示器胶囊条
 *
 * 遵循 ui-craft (C:\Users\Administrator\Desktop\ui-craft-master) 工业级设计规范：
 * - Rule 1 (Content carries the screen; chrome is nearly invisible): 极简胶囊，不喧宾夺主
 * - Rule 4 & 10 (Type does the hierarchy, not boxes): 利用字体字阶（SemiBold 12sp + Monospace 11sp）自然分层
 * - Rule 7 & 15 (Colour is a signal, not paint): 磨砂低饱和度微调背景，微弱 0.5dp 边缘弱化
 * - Rule 13 (Buttons & chips are compact): 紧凑 30dp 胶囊高度，12dp 内边距，44dp 友好触控
 */
@Composable
fun ProjectContextPillBar(
    project: ProjectEntity,
    folderPath: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ChatColors.toolCapsuleBg.copy(alpha = 0.85f),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 0.5.dp,
                    color = ChatColors.separator.copy(alpha = 0.6f),
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
                // 项目图标 / 文件夹图标
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // 项目名称
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = ChatColors.primaryText
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 弱化分隔圆点
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = ChatColors.tertiaryText,
                        fontSize = 11.sp
                    )
                )

                // 工作目录（等宽字体展示）
                Text(
                    text = folderPath,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 微型展开指示箭头
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

/**
 * ProjectContextSheet — 会话项目上下文抽屉
 *
 * 遵循 ui-craft Rule 8 (Sheets are the app's second surface):
 * 标准 36x4 抓手、清晰字阶标题、信息行与快捷动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectContextSheet(
    project: ProjectEntity,
    folderPath: String,
    onDismissRequest: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSwitchProject: (() -> Unit)? = null,
    onUnlinkProject: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = ChatColors.secondaryBg,
        dragHandle = {
            // ui-craft Rule 8: grabber 36×4
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
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // 头部：项目名称与图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = ChatColors.primaryText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!project.description.isNullOrBlank()) {
                        Text(
                            text = project.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = ChatColors.secondaryText
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 工作路径展示卡片
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ChatColors.toolCapsuleBg.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, ChatColors.separator.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Linux 工作目录 (cwd)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = ChatColors.tertiaryText
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = folderPath,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ChatColors.primaryText
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(folderPath))
                            Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制路径",
                            tint = ChatColors.secondaryText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 项目专属规约与隔离记忆状态 (ui-craft Rule 4 & 10: Type does the hierarchy)
            val hostDir = remember(project.id) {
                com.openminis.app.sandbox.RootfsManager.getInstance(context).getProjectDir(project.name, project.linuxPath, project.id)
            }
            val promptInfo = remember(project.id) {
                com.openminis.app.agent.ProjectPromptManager.loadProjectPrompt(hostDir)
            }
            val projectMemBytes = remember(project.id) {
                val f = java.io.File(hostDir, ".hark/memory/PROJECT.md")
                if (f.exists()) runCatching { f.length() }.getOrDefault(0L) else 0L
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ChatColors.toolCapsuleBg.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, ChatColors.separator.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // 项目提示词状态
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "项目专属规约:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = ChatColors.tertiaryText
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (promptInfo != null) {
                                "${promptInfo.file.name} (${promptInfo.mode.name.lowercase()}, ${promptInfo.content.length} 字)"
                            } else {
                                "未配置 (默认继承全局 SYSTEM.md)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = if (promptInfo != null) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (promptInfo != null) MaterialTheme.colorScheme.primary else ChatColors.secondaryText
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 项目记忆状态
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "项目独立记忆:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = ChatColors.tertiaryText
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (projectMemBytes > 0) {
                                ".hark/memory/PROJECT.md (${projectMemBytes} B, 隔离防串台)"
                            } else {
                                "尚无记录 (对话中将自动隔离保存)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = if (projectMemBytes > 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (projectMemBytes > 0) MaterialTheme.colorScheme.primary else ChatColors.secondaryText
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            // 快捷动作列表 (ui-craft Rule 4: rows 44-52 pt)
            ProjectActionRow(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "浏览项目目录文件",
                subtitle = "查看与编辑项目内的代码与素材",
                onClick = onBrowseFiles
            )

            ProjectActionRow(
                icon = Icons.Default.Terminal,
                title = "在项目目录下打开终端",
                subtitle = "在 Alpine 环境中直接执行 Shell",
                onClick = onOpenTerminal
            )

            if (onSwitchProject != null) {
                ProjectActionRow(
                    icon = Icons.Default.SwapHoriz,
                    title = "切换会话所属项目",
                    subtitle = "将当前会话关联至其他项目",
                    onClick = onSwitchProject
                )
            }

            if (onUnlinkProject != null) {
                ProjectActionRow(
                    icon = Icons.Default.LinkOff,
                    title = "从项目中移出",
                    subtitle = "转为普通独立会话",
                    onClick = onUnlinkProject,
                    isDestructive = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProjectActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else ChatColors.primaryText
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    color = ChatColors.tertiaryText
                )
            )
        }
    }
}
