package com.openminis.app.agent.subagent

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 线程安全的子代理生命周期注册表与并发管理器
 * 借鉴 hermes _REGISTRY 与 deepseek SubagentRuntime 设计
 */
object SubagentRegistry {

    @Immutable
    data class SubagentRecord(
        val handle: SubagentHandle,
        val state: SubagentState = SubagentState.PENDING,
        val result: SubagentResult? = null,
        val job: Job? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val currentStep: String? = null,
        val liveLogs: List<String> = emptyList(),
        val childSessionId: String? = null,
    )

    private val records = ConcurrentHashMap<String, SubagentRecord>()

    // 全局子代理工作协程域
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 控制同时运行的子代理最大并发度（默认上限 8）
    val concurrencyLimiter = Semaphore(8)

    // 针对每个父会话的子代理列表变化通知 Flow
    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<List<SubagentRecord>>>()

    // 节流作业表：用于将 100ms 内的多次高频状态变化合并为单次通知，彻底消除高频重组引起的 UI 帧抖动
    private val pendingNotifyJobs = ConcurrentHashMap<String, Job>()

    // 人工纠偏通道映射表：subagentId -> Channel
    private val interventionChannels = ConcurrentHashMap<String, Channel<String>>()

    private fun dispatchSessionChange(parentSessionId: String) {
        val flow = sessionFlows.computeIfAbsent(parentSessionId) { MutableStateFlow(emptyList()) }
        val sessionRecords = records.values
            .filter { it.handle.parentSessionId == parentSessionId }
            .sortedBy { it.createdAt }
        flow.value = sessionRecords
    }

    fun notifySessionChange(parentSessionId: String, immediate: Boolean = false) {
        if (immediate) {
            pendingNotifyJobs.remove(parentSessionId)?.cancel()
            dispatchSessionChange(parentSessionId)
            return
        }
        val existing = pendingNotifyJobs[parentSessionId]
        if (existing == null || existing.isCompleted) {
            val job = scope.launch {
                delay(100L)
                pendingNotifyJobs.remove(parentSessionId)
                dispatchSessionChange(parentSessionId)
            }
            pendingNotifyJobs[parentSessionId] = job
        }
    }

    /**
     * 注册新的子代理
     */
    fun register(handle: SubagentHandle, job: Job? = null, childSessionId: String? = null): SubagentRecord {
        val record = SubagentRecord(
            handle = handle,
            state = SubagentState.PENDING,
            job = job,
            childSessionId = childSessionId,
        )
        records[handle.id] = record
        notifySessionChange(handle.parentSessionId, immediate = true)
        return record
    }

    /**
     * 绑定或更新子代理的 Job
     */
    fun bindJob(id: String, job: Job) {
        records.computeIfPresent(id) { _, rec ->
            rec.copy(job = job, updatedAt = System.currentTimeMillis())
        }
    }

    /**
     * 关联创建好的独立子会话 ID
     */
    fun bindChildSession(id: String, childSessionId: String) {
        val updated = records.computeIfPresent(id) { _, rec ->
            rec.copy(childSessionId = childSessionId, updatedAt = System.currentTimeMillis())
        }
        updated?.let { notifySessionChange(it.handle.parentSessionId, immediate = false) }
    }

    /**
     * 记录实时进度或执行步骤
     */
    fun updateProgress(id: String, currentStep: String, logEntry: String? = null) {
        val updated = records.computeIfPresent(id) { _, rec ->
            val newLogs = if (logEntry != null) {
                if (rec.liveLogs.size >= 60) rec.liveLogs.drop(1) + logEntry
                else rec.liveLogs + logEntry
            } else rec.liveLogs
            rec.copy(
                currentStep = currentStep,
                liveLogs = newLogs,
                updatedAt = System.currentTimeMillis(),
            )
        }
        updated?.let { notifySessionChange(it.handle.parentSessionId, immediate = false) }
    }

    /**
     * 更新子代理状态
     */
    fun updateState(id: String, state: SubagentState, result: SubagentResult? = null) {
        val updated = records.computeIfPresent(id) { _, rec ->
            rec.copy(
                state = state,
                result = result ?: rec.result,
                updatedAt = System.currentTimeMillis(),
            )
        }
        updated?.let { notifySessionChange(it.handle.parentSessionId, immediate = state.isTerminal) }
    }

    /**
     * 获取指定 ID 的状态
     */
    fun getRecord(id: String): SubagentRecord? = records[id]

    /**
     * 获取或创建针对该子代理的人工纠偏指令通信通道
     */
    fun getOrCreateInterventionChannel(id: String): Channel<String> {
        return interventionChannels.computeIfAbsent(id) {
            Channel(Channel.BUFFERED)
        }
    }

    /**
     * 发送人工干预指令
     */
    fun sendIntervention(id: String, instruction: String): Boolean {
        val record = records[id] ?: return false
        val channel = getOrCreateInterventionChannel(id)
        val sent = channel.trySend(instruction).isSuccess
        updateProgress(id, "收到人工干预指令", "【人工干预】: $instruction")
        return sent
    }

    /**
     * 中断或取消子代理
     */
    fun cancel(id: String, reason: String = "User requested cancellation"): Boolean {
        val record = records[id] ?: return false
        if (record.state.isTerminal) return false

        record.job?.cancel(kotlinx.coroutines.CancellationException(reason))
        updateState(
            id,
            SubagentState.CANCELLED,
            SubagentResult(
                handle = record.handle,
                state = SubagentState.CANCELLED,
                summary = "[已取消: $reason]",
                startedAt = record.createdAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = reason,
            )
        )
        return true
    }

    /**
     * 取消指定会话下的所有子代理
     */
    fun cancelAllForSession(parentSessionId: String, reason: String = "Session cancelled"): Int {
        var count = 0
        records.values
            .filter { it.handle.parentSessionId == parentSessionId && !it.state.isTerminal }
            .forEach {
                if (cancel(it.handle.id, reason)) count++
            }
        return count
    }

    /**
     * 订阅特定会话的子代理状态流（用于 UI 实时更新）
     */
    fun observeSessionSubagents(parentSessionId: String): StateFlow<List<SubagentRecord>> {
        val flow = sessionFlows.computeIfAbsent(parentSessionId) { MutableStateFlow(emptyList()) }
        val sessionRecords = records.values
            .filter { it.handle.parentSessionId == parentSessionId }
            .sortedBy { it.createdAt }
        flow.value = sessionRecords
        return flow.asStateFlow()
    }

    /**
     * 获取指定会话的所有已完成结果
     */
    fun getSessionResults(parentSessionId: String): List<SubagentResult> {
        return records.values
            .filter { it.handle.parentSessionId == parentSessionId }
            .mapNotNull { it.result }
    }

    /**
     * 计算该会话所有子代理消耗的总费用
     */
    fun getSessionTotalCostUsd(parentSessionId: String): Double {
        return getSessionResults(parentSessionId).sumOf { it.estimatedCostUsd ?: 0.0 }
    }

    /**
     * 计算该会话所有子代理消耗的总 Token
     */
    fun getSessionTotalTokens(parentSessionId: String): Int {
        return getSessionResults(parentSessionId).sumOf { it.tokensUsed }
    }

    /**
     * 清理过期记录（保留最近1小时）
     */
    fun cleanExpired(maxAgeMs: Long = 3600_000L) {
        val now = System.currentTimeMillis()
        val expiredKeys = records.filter { (_, rec) ->
            rec.state.isTerminal && (now - rec.updatedAt > maxAgeMs)
        }.keys

        expiredKeys.forEach { key ->
            val removed = records.remove(key)
            interventionChannels.remove(key)?.close()
            removed?.let { notifySessionChange(it.handle.parentSessionId, immediate = true) }
        }
    }
}
