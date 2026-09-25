package com.openminis.app.sandbox

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内存运行压力水位评级
 */
enum class MemoryPressure {
    NORMAL,    // 系统运行内存余量充足 (> 1000 MB 或 > 20%)
    MODERATE,  // 运行内存趋紧 (500 ~ 1000 MB)，建议控制并发
    HIGH,      // 运行内存紧缺 (200 ~ 500 MB)，存在 Android LMK 强杀隐患
    CRITICAL   // 运行内存濒危 (< 200 MB)，随时可能触发 OOM 杀进程
}

/**
 * SandboxMetricsState — 沙盒运行环境指标快照 (双轨存储与实时内存透析架构)
 */
data class SandboxMetricsState(
    val isPRootReady: Boolean = false,
    val runningServicesCount: Int = 0,
    val services: List<DaemonServiceInfo> = emptyList(),
    val appMemoryUsedMb: Long = 0L,

    // 系统物理运行内存 (RAM)
    val systemMemoryFreeMb: Long = 0L,          // 物理可用剩余 (如 1433 MB)
    val systemMemoryTotalMb: Long = 0L,         // 物理总内存 (如 8192 MB)
    val systemMemoryFreePercent: Int = 0,       // 剩余百分比 (如 18%)

    // 外部机身大存储 (Shared External Storage，40~128GB Flash，大项目/数据集持久化)
    val externalFreeGb: Double = 0.0,
    val externalTotalGb: Double = 0.0,

    // 内部私有存储 (Internal Storage，/data/data/com.hark.app，Alpine 系统与核心依赖)
    val internalFreeGb: Double = 0.0,
    val internalTotalGb: Double = 0.0,

    // Alpine Rootfs 实际占用磁盘大小 (懒加载异步测算)
    val rootfsUsedGb: Double = 0.0,
    val rootfsUsedGbLoaded: Boolean = false,

    // 已 Bind Mount 挂载的本机文件夹数量
    val mountedFolderCount: Int = 0,

    // 内存压力等级
    val memoryPressure: MemoryPressure = MemoryPressure.NORMAL,

    // 兼容历史调用方，默认映射至外部大存储
    val storageFreeGb: Double = externalFreeGb,
    val storageTotalGb: Double = externalTotalGb,

    val hasHungProcesses: Boolean = false,
    val lastUpdated: Long = 0L,
)

/**
 * SandboxMetricsCollector — 轻量化双轨环境指标采集器
 *
 * 核心升级：
 * - 纯异步 I/O (Dispatchers.IO) 采集，严禁阻塞主线程；
 * - 3 秒静默轮询心跳 (Ticker)，保障数据无感、实时刷新；
 * - 结合 ActivityManager.MemoryInfo 与 /proc/meminfo，精准获取物理 RAM 总量、余量与剩余百分比；
 * - 优先使用 getExternalFilesDir(null) 探测外部 Flash 大存储，100% 免疫 Android 11~15 权限报错。
 */
object SandboxMetricsCollector {

    private const val TAG = "SandboxMetricsCollector"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private val _metrics = MutableStateFlow(SandboxMetricsState())
    val metrics: StateFlow<SandboxMetricsState> = _metrics.asStateFlow()

    private var lastCollectTime = 0L
    private var lastRootfsCheckTime = 0L
    private var cachedRootfsUsedGb = 0.0
    private var cachedRootfsLoaded = false
    private var isCalculatingRootfs = false
    private var lastReportedPressure = MemoryPressure.NORMAL
    private var tickerJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshMetrics(force = true)
        startTicker()
    }

    /**
     * 启动 3 秒无感实时心跳轮询
     */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(3000L)
                try {
                    val state = collectFromSystem()
                    _metrics.value = state
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "Ticker collect error: ${e.message}")
                }
            }
        }
    }

    /**
     * 手动请求刷新当前沙盒运行指标
     */
    fun refreshMetrics(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCollectTime < 1500L)) {
            return
        }
        lastCollectTime = now

        scope.launch {
            val state = collectFromSystem()
            _metrics.value = state
        }
    }

    private suspend fun collectFromSystem(): SandboxMetricsState = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext SandboxMetricsState()

        val runtime = Runtime.getRuntime()
        val appMemoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        // 1. 机身外部共享大存储 (优先读取 getExternalFilesDir(null)，全 Android 版本零权限障碍)
        var externalFreeGb = 0.0
        var externalTotalGb = 0.0
        try {
            val candidateDir = appContext.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory()
            if (candidateDir != null && candidateDir.exists()) {
                val stat = StatFs(candidateDir.absolutePath)
                val blockSize = stat.blockSizeLong
                externalFreeGb = (stat.availableBlocksLong * blockSize).toDouble() / (1024 * 1024 * 1024)
                externalTotalGb = (stat.blockCountLong * blockSize).toDouble() / (1024 * 1024 * 1024)
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Failed to read external storage StatFs: ${e.message}")
        }

        // 2. 内部私有存储 (/data/user/0/com.hark.app/files，Alpine Rootfs 所在地)
        var internalFreeGb = 0.0
        var internalTotalGb = 0.0
        try {
            val intStat = StatFs(appContext.filesDir.absolutePath)
            val blockSize = intStat.blockSizeLong
            internalFreeGb = (intStat.availableBlocksLong * blockSize).toDouble() / (1024 * 1024 * 1024)
            internalTotalGb = (intStat.blockCountLong * blockSize).toDouble() / (1024 * 1024 * 1024)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Failed to read internal storage StatFs: ${e.message}")
        }

        // 容错兜底：若外部存储读取为 0，回退为内部存储
        if (externalTotalGb <= 0.0) {
            externalFreeGb = internalFreeGb
            externalTotalGb = internalTotalGb
        }

        // 3. 系统可用物理 RAM (标准 ActivityManager.MemoryInfo + /proc/meminfo 双重保障)
        var memFreeMb = 0L
        var memTotalMb = 0L

        try {
            val actManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val actMemInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(actMemInfo)
            if (actMemInfo.totalMem > 0L) {
                memFreeMb = actMemInfo.availMem / (1024 * 1024)
                memTotalMb = actMemInfo.totalMem / (1024 * 1024)
            }
        } catch (_: Exception) {}

        // /proc/meminfo 兜底
        if (memTotalMb <= 0L) {
            try {
                val memInfoFile = File("/proc/meminfo")
                if (memInfoFile.exists()) {
                    memInfoFile.forEachLine { line ->
                        if (line.startsWith("MemTotal:")) {
                            val kb = line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
                            memTotalMb = kb / 1024
                        } else if (line.startsWith("MemAvailable:")) {
                            val kb = line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
                            memFreeMb = kb / 1024
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val memFreePercent = if (memTotalMb > 0L) {
            ((memFreeMb * 100) / memTotalMb).toInt().coerceIn(0, 100)
        } else {
            0
        }

        // 4. 内存压力等级评估
        val currentPressure = when {
            memFreeMb in 1..199 || (memFreePercent in 1..5) -> MemoryPressure.CRITICAL
            memFreeMb in 200..499 || (memFreePercent in 6..12) -> MemoryPressure.HIGH
            memFreeMb in 500..999 || (memFreePercent in 13..20) -> MemoryPressure.MODERATE
            else -> MemoryPressure.NORMAL
        }

        // 当内存压力等级变化时，写信号文件到 /var/hark/shared/memory_pressure 供 Linux/Agent 感知
        if (currentPressure != lastReportedPressure) {
            lastReportedPressure = currentPressure
            try {
                val sharedDir = File(appContext.filesDir, "hark-global/shared")
                if (sharedDir.exists() || sharedDir.mkdirs()) {
                    File(sharedDir, "memory_pressure").writeText(currentPressure.name)
                }
            } catch (_: Exception) {}
        }

        // 5. 守护服务列表与挂载文件夹计数
        val srvList = SandboxDaemonSupervisor.services.value
        val isBooted = PRootKernel.isBooted
        val mountedCount = PRootKernel.mountedFoldersStore?.entries?.value?.size ?: 0

        // 6. 异步懒加载测算 Alpine Rootfs 实际占用 (60 秒间隔，避免频繁磁盘 I/O 阻塞)
        val now = System.currentTimeMillis()
        if (!cachedRootfsLoaded || (now - lastRootfsCheckTime > 60000L)) {
            triggerRootfsSizeCalculation()
        }

        val roundedExtFree = Math.round(externalFreeGb * 10.0) / 10.0
        val roundedExtTotal = Math.round(externalTotalGb * 10.0) / 10.0
        val roundedIntFree = Math.round(internalFreeGb * 10.0) / 10.0
        val roundedIntTotal = Math.round(internalTotalGb * 10.0) / 10.0

        SandboxMetricsState(
            isPRootReady = isBooted,
            runningServicesCount = srvList.count { it.status == "running" },
            services = srvList,
            appMemoryUsedMb = appMemoryUsedMb,
            systemMemoryFreeMb = memFreeMb,
            systemMemoryTotalMb = memTotalMb,
            systemMemoryFreePercent = memFreePercent,
            externalFreeGb = roundedExtFree,
            externalTotalGb = roundedExtTotal,
            internalFreeGb = roundedIntFree,
            internalTotalGb = roundedIntTotal,
            rootfsUsedGb = cachedRootfsUsedGb,
            rootfsUsedGbLoaded = cachedRootfsLoaded,
            mountedFolderCount = mountedCount,
            memoryPressure = currentPressure,
            storageFreeGb = roundedExtFree,
            storageTotalGb = roundedExtTotal,
            hasHungProcesses = false,
            lastUpdated = now,
        )
    }

    private fun triggerRootfsSizeCalculation() {
        if (isCalculatingRootfs) return
        isCalculatingRootfs = true
        scope.launch(Dispatchers.IO) {
            try {
                val rootfsManager = RootfsManager.getInstance(appContext)
                val sizeBytes = rootfsManager.getRootfsSize()
                cachedRootfsUsedGb = Math.round((sizeBytes.toDouble() / (1024 * 1024 * 1024)) * 10.0) / 10.0
                cachedRootfsLoaded = true
                lastRootfsCheckTime = System.currentTimeMillis()

                // 更新当前 snapshot
                _metrics.value = _metrics.value.copy(
                    rootfsUsedGb = cachedRootfsUsedGb,
                    rootfsUsedGbLoaded = true
                )
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Failed to compute rootfs size: ${e.message}")
            } finally {
                isCalculatingRootfs = false
            }
        }
    }
}
