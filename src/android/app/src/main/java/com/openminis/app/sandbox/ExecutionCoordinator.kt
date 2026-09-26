package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }

        return mutex.withLock {
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                PRootKernel.boot(appContext)
            }

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId)

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                "$truncated\n(exit code: $exitCode)"
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/hark/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/hark/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/hark/attachments"]}")
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/hark/attachments"]

        // Session-specific directories
        val sessionBase = File(filesDir, "hark-sessions/$sessionId")
        listOf("attachments", "offloads", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/hark/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // Workspace directory:
        // [T-project-management] Check if this session is bound to a project (directly or via folder)
        val projectInfo = resolveProjectForSession(sessionId)
        if (projectInfo != null) {
            val rootfs = RootfsManager.getInstance(appContext)
            val projectHostDir = rootfs.getProjectDir(projectInfo.name, projectInfo.linuxPath, projectInfo.id)
            val linuxWorkspace = "/var/hark/workspace"
            mounts[linuxWorkspace] = projectHostDir.absolutePath
            PRootKernel.addBindMount(linuxWorkspace, projectHostDir.absolutePath)

            val projectLinuxPath = projectInfo.linuxPath?.takeIf { it.isNotBlank() } ?: "/var/hark/projects/${projectInfo.name}"
            mounts[projectLinuxPath] = projectHostDir.absolutePath
            PRootKernel.addBindMount(projectLinuxPath, projectHostDir.absolutePath)
            Log.i(TAG, "[$sessionId] Bound to project '${projectInfo.name}' (id=${projectInfo.id}) -> $projectLinuxPath")
        } else {
            val hostDir = File(sessionBase, "workspace").also { it.mkdirs() }
            val linuxPath = "/var/hark/workspace"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, "[diag] buildSessionBindMounts sessionId=$sessionId " +
            "attachments: prev=$prevAttachments new=${mounts["/var/hark/attachments"]}")

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs hark-mcp-cli — /var/hark/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `hark-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, "hark-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/hark/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/hark/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/hark/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        val shell = shells.remove(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            val shell = shells.remove(sessionId)
            // T124a: snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(sessionId)
            shell?.stop()
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * 急救排空与重置：强制释放指定会话的 Mutex、销毁当前阻塞的 Shell 进程，
     * 并重置环境快照，使下一次指令能够无阻塞地直接进入干净的 Shell。
     */
    fun emergencyResetSession(sessionId: String) {
        Log.w(TAG, "[$sessionId] Emergency reset requested")
        val shell = shells.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        // 重新初始化 Mutex，防止由于协程挂起导致的 Mutex 永久占用
        mutexes[sessionId] = Mutex()
        Log.i(TAG, "[$sessionId] Emergency reset completed")
    }

    /**
     * 孤儿僵死进程清理：直接在 Host 端扫描 PRoot /proc 进程树，终止挂起的
     * 孤儿/失控程序（如挂起的 sleep、curl、wget 等），且绝不误杀主应用进程与受监管的守护服务。
     * 全程脱离 Shell Mutex，即使 Shell 彻底死锁也能实现即时救活。
     */
    suspend fun reapOrphanProcesses(sessionId: String): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val myPid = android.os.Process.myPid()
            val protectedPids = SandboxDaemonSupervisor.getProtectedPids()
            var reapedCount = 0

            val procDir = File("/proc")
            val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()

            for (p in pidDirs) {
                val pid = p.name.toIntOrNull() ?: continue
                if (pid <= 1 || pid == myPid || protectedPids.contains(pid)) continue

                // Check PPID (parent PID) to protect child/worker processes spawned by daemons
                val statText = runCatching { File(p, "stat").readText() }.getOrNull() ?: ""
                val ppid = statText.split(Regex("\\s+")).getOrNull(3)?.toIntOrNull() ?: 0
                if (ppid > 0 && protectedPids.contains(ppid)) continue

                val cmdline = runCatching {
                    File(p, "cmdline").readText()
                }.getOrNull()?.replace('\u0000', ' ') ?: ""

                val comm = runCatching {
                    File(p, "comm").readText().trim()
                }.getOrNull() ?: ""

                val full = "$comm $cmdline".lowercase()
                // Only target genuine runaway client utilities (sleep, curl, wget, tail), never servers/interpreters
                val isHungTarget = listOf("sleep", "curl", "wget", "tail").any { full.contains(it) }

                if (isHungTarget) {
                    try {
                        android.os.Process.sendSignal(pid, 9)
                        reapedCount++
                        Log.i(TAG, "Reaped orphan process pid=$pid ($comm: ${cmdline.take(60)})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to kill pid=$pid: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Reaped total $reapedCount orphan processes directly from /proc")
            reapedCount
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reap orphan processes: ${e.message}")
            0
        }
    }

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }

    private data class ProjectMountInfo(
        val id: String,
        val name: String,
        val linuxPath: String?,
    )

    private fun resolveProjectForSession(sessionId: String): ProjectMountInfo? {
        return runCatching {
            val dao = com.openminis.app.data.db.AppDatabase.getInstance(appContext).chatDao()
            // First check if sessionId encodes draft project: __prj__<projectId>
            val draftProjectId = if (sessionId.startsWith("__new__")) {
                Regex("__prj__(.*?)(?=__|$)").find(sessionId)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            } else null
            val draftFolderId = if (sessionId.startsWith("__new__")) {
                Regex("__fld__(.*?)(?=__|$)").find(sessionId)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            } else null

            var projectId = draftProjectId
            if (projectId == null && draftFolderId != null) {
                val folder = kotlinx.coroutines.runBlocking { dao.getFolder(draftFolderId) }
                projectId = folder?.projectId
            }
            if (projectId == null) {
                // If not in draft query, check session row in DB
                val session = kotlinx.coroutines.runBlocking { dao.getSession(sessionId) }
                projectId = session?.projectId
                if (projectId == null && session?.folderId != null) {
                    val folder = kotlinx.coroutines.runBlocking { dao.getFolder(session.folderId) }
                    projectId = folder?.projectId
                }
            }

            if (projectId != null) {
                val project = kotlinx.coroutines.runBlocking { dao.getProject(projectId) }
                if (project != null) {
                    return@runCatching ProjectMountInfo(
                        id = project.id,
                        name = project.name,
                        linuxPath = project.linuxPath,
                    )
                }
            }
            null
        }.getOrNull()
    }
}
