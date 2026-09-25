package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DaemonServiceInfo — PRoot 沙盒长驻守护服务实体
 */
data class DaemonServiceInfo(
    val name: String,
    val pid: Int,
    val port: Int = 0,
    val dir: String = "",
    val cmd: String = "",
    val startedAt: Long = 0L,
    val status: String = "running",
    val logFile: String = "",
    val isPortListening: Boolean = false,
)

/**
 * SandboxDaemonSupervisor — PRoot Linux 沙盒全局服务守护者
 *
 * 核心职责：
 * 1. 监控与管理由 `hark-service` 托管的全局跨会话后台常驻服务；
 * 2. 直接访问 `/var/hark/shared/services/` 对应的 Host 目录 (`hark-global/shared/services`)，
 *    实现纳秒级免 Shell 状态同步；
 * 3. 定期维护死进程清理（Reap Dead PIDs）与提供日志实时读取；
 * 4. 保护守护服务在会话切换、Shell 重启或会话销毁时不被殃及。
 */
object SandboxDaemonSupervisor {

    private const val TAG = "SandboxDaemonSupervisor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var servicesDir: File
    private lateinit var registryFile: File
    private lateinit var logsDir: File
    private lateinit var pidsDir: File

    private val _services = MutableStateFlow<List<DaemonServiceInfo>>(emptyList())
    val services: StateFlow<List<DaemonServiceInfo>> = _services.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val globalShared = File(appContext.filesDir, "hark-global/shared")
        servicesDir = File(globalShared, "services").also { it.mkdirs() }
        registryFile = File(servicesDir, "registry.json")
        logsDir = File(servicesDir, "logs").also { it.mkdirs() }
        pidsDir = File(servicesDir, "pids").also { it.mkdirs() }

        if (!registryFile.exists()) {
            runCatching { registryFile.writeText("[]") }
        }

        refreshServices()
    }

    /**
     * 刷新并同步当前运行的所有守护服务
     */
    fun refreshServices() {
        scope.launch {
            val list = queryServicesFromDisk()
            _services.value = list
        }
    }

    /**
     * 实时同步获取所有守护服务的有效 PID 集合（直接读取磁盘 .pid 文件与内存缓存），
     * 供孤儿进程回收器 (reapOrphanProcesses) 作为绝对受保护名单使用。
     */
    fun getProtectedPids(): Set<Int> {
        val result = mutableSetOf<Int>()
        if (::pidsDir.isInitialized && pidsDir.exists()) {
            val pidFiles = pidsDir.listFiles { _, name -> name.endsWith(".pid") } ?: emptyArray()
            for (f in pidFiles) {
                val pid = runCatching { f.readText().trim().toIntOrNull() }.getOrNull()
                if (pid != null && pid > 1) {
                    result.add(pid)
                }
            }
        }
        _services.value.forEach { if (it.pid > 1) result.add(it.pid) }
        return result
    }

    private suspend fun queryServicesFromDisk(): List<DaemonServiceInfo> = withContext(Dispatchers.IO) {
        if (!::servicesDir.isInitialized) return@withContext emptyList()
        val results = mutableListOf<DaemonServiceInfo>()

        try {
            val entriesDir = File(servicesDir, "entries")
            if (entriesDir.exists() && entriesDir.isDirectory) {
                val entryFiles = entriesDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
                for (file in entryFiles) {
                    try {
                        val text = file.readText().trim()
                        if (text.isEmpty()) continue
                        val obj = JSONObject(text)
                        val name = obj.optString("name", file.nameWithoutExtension)
                        val pid = obj.optInt("pid", -1)
                        val port = obj.optInt("port", 0)
                        val dir = obj.optString("dir", "")
                        val cmd = obj.optString("cmd", "")
                        val startedAt = obj.optLong("startedAt", 0L)
                        val logFile = "/var/hark/shared/services/logs/$name.log"

                        val isAlive = if (pid > 0) File("/proc/$pid").exists() else false
                        val pidFile = File(pidsDir, "$name.pid")

                        if (isAlive) {
                            results.add(
                                DaemonServiceInfo(
                                    name = name,
                                    pid = pid,
                                    port = port,
                                    dir = dir,
                                    cmd = cmd,
                                    startedAt = startedAt,
                                    status = "running",
                                    logFile = logFile,
                                    isPortListening = port > 0,
                                )
                            )
                        } else {
                            file.delete()
                            if (pidFile.exists()) pidFile.delete()
                            AppLogger.info(TAG, "Reaped dead service '$name' (pid=$pid)")
                        }
                    } catch (fe: Exception) {
                        AppLogger.warning(TAG, "Failed to read entry file ${file.name}: ${fe.message}")
                    }
                }
                return@withContext results
            }

            // Fallback to legacy registry.json if entries dir not yet created
            if (!registryFile.exists()) return@withContext emptyList()
            val text = registryFile.readText().trim()
            if (text.isEmpty() || text == "[]") return@withContext emptyList()

            val jsonArray = JSONArray(text)
            val updatedArray = JSONArray()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val name = obj.optString("name", "")
                val pid = obj.optInt("pid", -1)
                val port = obj.optInt("port", 0)
                val dir = obj.optString("dir", "")
                val cmd = obj.optString("cmd", "")
                val startedAt = obj.optLong("startedAt", 0L)
                val logFile = "/var/hark/shared/services/logs/$name.log"

                val isAlive = if (pid > 0) File("/proc/$pid").exists() else false
                val pidFile = File(pidsDir, "$name.pid")

                if (isAlive) {
                    results.add(
                        DaemonServiceInfo(
                            name = name,
                            pid = pid,
                            port = port,
                            dir = dir,
                            cmd = cmd,
                            startedAt = startedAt,
                            status = "running",
                            logFile = logFile,
                            isPortListening = port > 0,
                        )
                    )
                    updatedArray.put(obj)
                } else {
                    if (pidFile.exists()) pidFile.delete()
                    AppLogger.info(TAG, "Reaped dead service '$name' (pid=$pid)")
                }
            }

            if (updatedArray.length() != jsonArray.length()) {
                registryFile.writeText(updatedArray.toString())
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Error querying services: ${e.message}")
        }

        results
    }

    /**
     * 读取指定服务的最新日志
     */
    fun readServiceLogs(name: String, maxLines: Int = 100): String {
        return try {
            val logFile = File(logsDir, "$name.log")
            if (!logFile.exists()) return "(No logs found for $name)"
            val lines = logFile.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}"
        }
    }

    /**
     * 停止指定守护服务：支持直接向 Linux PID 发送信号（确保即使 Shell 挂起也能可靠终止），
     * 并同步调用 `hark-service stop` 清理注册表与状态文件。
     */
    suspend fun stopService(name: String, sessionId: String? = null): Boolean = withContext(Dispatchers.IO) {
        var stopped = false
        val pidFile = File(pidsDir, "$name.pid")
        val pid = if (pidFile.exists()) pidFile.readText().trim().toIntOrNull() else null

        // 1. 若 PID 存在且存活，通过 Host 进程信号先行优雅终止，避免受限于可能阻塞的 Shell
        if (pid != null && pid > 0 && File("/proc/$pid").exists()) {
            try {
                android.os.Process.sendSignal(pid, 15) // SIGTERM
                var waited = 0
                while (File("/proc/$pid").exists() && waited < 10) {
                    kotlinx.coroutines.delay(100)
                    waited++
                }
                if (File("/proc/$pid").exists()) {
                    android.os.Process.sendSignal(pid, 9) // SIGKILL
                }
                stopped = true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Direct signal termination failed for $name (pid=$pid): ${e.message}")
            }
        }

        // 2. 清理服务 PID 文件与条目描述
        if (pidFile.exists()) pidFile.delete()
        val entryFile = File(servicesDir, "entries/$name.json")
        if (entryFile.exists()) entryFile.delete()

        // 3. 在沙盒内执行 hark-service stop 确保注册表与任何衍生子进程清理
        try {
            val sid = sessionId ?: "supervisor_${System.currentTimeMillis()}"
            val cmd = "hark-service stop \"$name\""
            val result = ExecutionCoordinator.execute(sid, cmd, timeout = 3000L)
            if (result.exitCode == 0) stopped = true
        } catch (_: Exception) {}

        refreshServices()
        stopped
    }

    /**
     * 停止所有守护服务
     */
    suspend fun stopAllServices(sessionId: String? = null) = withContext(Dispatchers.IO) {
        val current = _services.value
        for (srv in current) {
            stopService(srv.name, sessionId)
        }
    }

    /**
     * 检查某个端口是否已被后台服务占用
     */
    fun isPortInUse(port: Int): Boolean {
        if (port <= 0) return false
        return _services.value.any { it.port == port && it.status == "running" }
    }
}
