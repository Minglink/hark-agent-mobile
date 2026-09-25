package com.openminis.app.ui.sandbox

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.SafMountHelper
import com.openminis.app.sandbox.DaemonServiceInfo
import com.openminis.app.sandbox.MemoryPressure
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SandboxDaemonSupervisor
import com.openminis.app.sandbox.SandboxMetricsState
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.launch

/**
 * SandboxMonitorSheet — PRoot Linux 沙盒运行环境控制中心 (窄屏自适应与高兼容重构版)
 *
 * 遵循 ui-craft (C:\Users\Administrator\Desktop\ui-craft-master) 规范：
 * - Rule 4 & 10 (Type does the hierarchy): 统一纵向排版，绝不在窄屏上并列挤压
 * - Rule 8 (Sheets are the app's second surface): 标准 36x4 抓手、× 关闭按键
 * - Rule 9 (Empty states are one sentence and one move): 一句话一动作
 * - 强化兼容性：跨品牌安卓机型 SAF 容错与重名自增
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxMonitorSheet(
    metrics: SandboxMetricsState,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onEmergencyResetShell: () -> Unit,
    onReapOrphans: suspend () -> Int,
    onOpenTerminal: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var viewingLogsForService by remember { mutableStateOf<String?>(null) }
    var serviceLogsText by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    // 读取已挂载的外部大存储文件夹
    val store = PRootKernel.mountedFoldersStore
    val mountedEntries by (store?.entries?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

    // SAF 文件夹选取回调 (多机型高兼容与自动防重名)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (uri.authority != "com.android.externalstorage.documents") {
            Toast.makeText(context, "请在文件管理器左侧栏选择「本设备/内部存储」下的文件夹", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (!SafMountHelper.handlePickerResult(context, uri)) {
            Toast.makeText(context, "未能获取该目录的读写授权", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        val rawDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val leaf = rawDocId.substringAfterLast(':', uri.lastPathSegment ?: "folder")
            .substringAfterLast('/')
            .ifEmpty { "folder" }
        var candidateName = leaf.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32).ifEmpty { "mount" }

        scope.launch {
            val storeInstance = PRootKernel.mountedFoldersStore ?: return@launch
            val existing = storeInstance.entries.value.map { it.name.lowercase() }
            if (candidateName.lowercase() in existing) {
                var counter = 2
                while ("${candidateName}_$counter".lowercase() in existing) {
                    counter++
                }
                candidateName = "${candidateName}_$counter"
            }

            val entry = storeInstance.add(uri, candidateName, userAllowWrite = true)
            if (entry != null) {
                Toast.makeText(context, "已成功挂载至 /var/hark/mounts/${entry.name}", Toast.LENGTH_LONG).show()
                onRefresh()
            } else {
                Toast.makeText(context, "挂载失败：该目录不支持直接映射或已达挂载上限", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // 头部标题与操作 (ui-craft Rule 8)
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
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "沙盒环境控制中心",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = ChatColors.primaryText
                        )
                    )
                    Text(
                        text = "PRoot Linux 存储与内存透析、大项目挂载与常驻服务",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = ChatColors.secondaryText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = onDismissRequest, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 模块一：核心资源透析一体化卡片 (彻底解决窄屏并列挤压排版错乱)
            val pressureLabel = when (metrics.memoryPressure) {
                MemoryPressure.NORMAL -> "充裕"
                MemoryPressure.MODERATE -> "温和"
                MemoryPressure.HIGH -> "偏紧"
                MemoryPressure.CRITICAL -> "濒危"
            }
            val pressureColor = when (metrics.memoryPressure) {
                MemoryPressure.NORMAL -> Color(0xFF10B981)
                MemoryPressure.MODERATE -> Color(0xFF3B82F6)
                MemoryPressure.HIGH -> Color(0xFFF59E0B)
                MemoryPressure.CRITICAL -> Color(0xFFEF4444)
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ChatColors.toolCapsuleBg.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, ChatColors.separator.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 1. 机身大存储 (ROM 闪存)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.SdStorage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "机身大存储 (外部 Flash)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = ChatColors.primaryText
                                )
                            )
                            Text(
                                text = "总共 ${metrics.externalTotalGb} GB · 存放项目与数据",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    color = ChatColors.secondaryText
                                )
                            )
                        }
                        Text(
                            text = "${metrics.externalFreeGb} GB 剩余",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. 物理运行内存 (RAM 算力)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(pressureColor.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(pressureColor, CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "系统运行内存 (RAM)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = ChatColors.primaryText
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = pressureLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = pressureColor
                                    )
                                )
                            }
                            val ramTotalGbStr = String.format(java.util.Locale.US, "%.1f", metrics.systemMemoryTotalMb / 1024.0)
                            Text(
                                text = "物理总计 ${ramTotalGbStr} GB · APP 堆 ${metrics.appMemoryUsedMb} MB",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    color = ChatColors.secondaryText
                                )
                            )
                        }
                        val ramFreeGbStr = String.format(java.util.Locale.US, "%.1f", metrics.systemMemoryFreeMb / 1024.0)
                        val percentStr = if (metrics.systemMemoryFreePercent > 0) " (${metrics.systemMemoryFreePercent}%)" else ""
                        Text(
                            text = "${ramFreeGbStr} GB 剩余$percentStr",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ChatColors.primaryText
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. Linux 核心与私有区 (/data)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = ChatColors.secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Linux 核心私有区 (/data)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = ChatColors.primaryText
                                )
                            )
                            val rootfsText = if (metrics.rootfsUsedGbLoaded) "${metrics.rootfsUsedGb} GB" else "测算中..."
                            Text(
                                text = "Rootfs 占用: $rootfsText · 原生可执行权限",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    color = ChatColors.secondaryText
                                )
                            )
                        }
                        Text(
                            text = "${metrics.internalFreeGb} GB 剩余",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ChatColors.secondaryText
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 模块二：本机大存储文件夹挂载 (External Folder Mounts)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "本机大存储挂载",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = ChatColors.primaryText
                    )
                )
                if (mountedEntries.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${mountedEntries.size})",
                        style = MaterialTheme.typography.labelSmall.copy(color = ChatColors.tertiaryText)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                // 挂载按钮 (ui-craft Rule 13)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.clickable {
                        folderPickerLauncher.launch(SafMountHelper.buildPickerIntent())
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "添加文件夹",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (mountedEntries.isEmpty()) {
                // ui-craft Rule 9: Empty states are one sentence and one move
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ChatColors.toolCapsuleBg.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { folderPickerLauncher.launch(SafMountHelper.buildPickerIntent()) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "未挂载本机外部文件夹",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = ChatColors.primaryText
                                )
                            )
                            Text(
                                text = "点击选取手机项目目录，直通 40~128GB 机身闪存",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = ChatColors.secondaryText
                                )
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mountedEntries.forEach { entry ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ChatColors.toolCapsuleBg.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, ChatColors.separator.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = ChatColors.primaryText
                                        )
                                    )
                                    Text(
                                        text = "/var/hark/mounts/${entry.name}",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    )
                                    if (entry.resolvedHostPath != null) {
                                        Text(
                                            text = entry.resolvedHostPath.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = ChatColors.tertiaryText),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // 卸载挂载按钮
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            store?.remove(entry.id)
                                            Toast.makeText(context, "已解除挂载: ${entry.name}", Toast.LENGTH_SHORT).show()
                                            onRefresh()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "解除挂载",
                                        tint = ChatColors.tertiaryText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 模块三：长驻守护服务 (Daemon Services)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "全局跨会话守护服务",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = ChatColors.primaryText
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(${metrics.services.size})",
                    style = MaterialTheme.typography.labelSmall.copy(color = ChatColors.tertiaryText)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (metrics.services.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ChatColors.toolCapsuleBg.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "当前无正在运行的常驻服务",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = ChatColors.primaryText
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "通过 `hark-service start` 启动的服务在此长效守护",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = ChatColors.secondaryText
                            )
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.services.forEach { srv ->
                        DaemonServiceItemCard(
                            service = srv,
                            onViewLogs = {
                                viewingLogsForService = srv.name
                                serviceLogsText = SandboxDaemonSupervisor.readServiceLogs(srv.name, 100)
                            },
                            onStop = {
                                scope.launch {
                                    isOperating = true
                                    val success = SandboxDaemonSupervisor.stopService(srv.name)
                                    isOperating = false
                                    if (success) {
                                        Toast.makeText(context, "服务已停止", Toast.LENGTH_SHORT).show()
                                        onRefresh()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // 日志查看浮层
            if (viewingLogsForService != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.9f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "日志: ${viewingLogsForService}",
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                            )
                            IconButton(
                                onClick = { viewingLogsForService = null },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = serviceLogsText.ifBlank { "(暂无日志内容)" },
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.Green),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))

            // 模块四：急救与运维操作 (Emergency Actions - ui-craft Rule 13)
            Text(
                text = "故障排查与沙盒急救",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = ChatColors.primaryText
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 急救重置 Shell 引擎 (36dp)
                Button(
                    onClick = {
                        onEmergencyResetShell()
                        Toast.makeText(context, "Shell 已急救重置并就绪", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("急救重置 Shell", fontSize = 11.sp)
                }

                // 2. 清理孤儿进程 (36dp)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val count = onReapOrphans()
                            Toast.makeText(context, "已清理 $count 个挂起孤儿进程", Toast.LENGTH_SHORT).show()
                            onRefresh()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Text("清理挂起进程", fontSize = 11.sp, color = ChatColors.primaryText)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3. 打开终端 (40dp)
            OutlinedButton(
                onClick = {
                    onDismissRequest()
                    onOpenTerminal()
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp), tint = ChatColors.primaryText)
                Spacer(modifier = Modifier.width(6.dp))
                Text("打开 Alpine Linux 交互终端", fontSize = 12.sp, color = ChatColors.primaryText)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DaemonServiceItemCard(
    service: DaemonServiceInfo,
    onViewLogs: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ChatColors.toolCapsuleBg.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, ChatColors.separator.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // 运行状态小圆点
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF34C759), CircleShape)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = ChatColors.primaryText
                    )
                )
                Text(
                    text = if (service.port > 0) "端口: :${service.port} · PID: ${service.pid}" else "PID: ${service.pid}",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ChatColors.secondaryText)
                )
            }

            // 查看日志按钮
            IconButton(onClick = onViewLogs, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = "日志",
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(15.dp)
                )
            }

            // 停止服务按钮
            IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "停止服务",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
