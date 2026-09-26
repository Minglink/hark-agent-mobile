package com.openminis.app.agent.subagent

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Multi-Subagent team delegation, isolation, and lifecycles.
 */
interface SubagentEngine {
    fun getTasksFlow(parentSessionId: String): Flow<List<SubagentTask>>
    suspend fun delegateTask(
        parentSessionId: String,
        title: String,
        role: String,
        prompt: String,
        taskExecutor: suspend (SubagentTask) -> String
    ): SubagentTask

    /**
     * Release all task state for a session when the chat is closed.
     * Prevents indefinite memory growth in long-running app sessions.
     * [P1-opt] Fix for unbounded tasksMap memory leak: entries were never evicted.
     */
    fun cleanupSession(parentSessionId: String)
}

object SubagentEngineImpl : SubagentEngine {

    private const val TAG = "SubagentEngine"

    // [P1-opt] Removed unused `val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
    // `delegateTask` is a suspend function — the caller's scope handles the coroutine lifecycle.
    // The dead scope was initializing a SupervisorJob + worker threads that were never used.

    // parentSessionId -> (taskId -> SubagentTask)
    private val tasksMap = ConcurrentHashMap<String, MutableStateFlow<Map<String, SubagentTask>>>()

    private fun getSessionFlow(parentSessionId: String): MutableStateFlow<Map<String, SubagentTask>> {
        return tasksMap.computeIfAbsent(parentSessionId) {
            MutableStateFlow(emptyMap())
        }
    }

    override fun getTasksFlow(parentSessionId: String): Flow<List<SubagentTask>> {
        return getSessionFlow(parentSessionId).asStateFlow()
            .map { it.values.sortedBy { t -> t.createdAtMs } }
    }

    override suspend fun delegateTask(
        parentSessionId: String,
        title: String,
        role: String,
        prompt: String,
        taskExecutor: suspend (SubagentTask) -> String
    ): SubagentTask {
        val taskId = "sub-" + UUID.randomUUID().toString().take(8)
        val initialTask = SubagentTask(
            id = taskId,
            parentSessionId = parentSessionId,
            title = title,
            role = role,
            prompt = prompt,
            status = SubagentStatus.RUNNING,
            createdAtMs = System.currentTimeMillis()
        )

        val flow = getSessionFlow(parentSessionId)
        flow.value = flow.value + (taskId to initialTask)
        AppLogger.info(TAG, "Spawned subagent [$role] id=$taskId for task: $title")

        var finishedTask = initialTask

        try {
            val resultOutput = taskExecutor(initialTask)
            finishedTask = initialTask.copy(
                status = SubagentStatus.COMPLETED,
                output = resultOutput,
                completedAtMs = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Subagent $taskId failed: ${t.localizedMessage ?: t.javaClass.simpleName}")
            finishedTask = initialTask.copy(
                status = SubagentStatus.FAILED,
                errorMessage = t.localizedMessage ?: t.javaClass.simpleName,
                completedAtMs = System.currentTimeMillis()
            )
        }

        flow.value = flow.value + (taskId to finishedTask)
        return finishedTask
    }

    /**
     * [P1-opt] Evict session state to prevent indefinite memory growth.
     * Call this when a chat session is closed or destroyed.
     */
    override fun cleanupSession(parentSessionId: String) {
        tasksMap.remove(parentSessionId)
        AppLogger.info(TAG, "Cleaned up subagent state for session $parentSessionId")
    }
}
