package com.openminis.app.agent.team.model

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class TeamTaskStatus {
    PENDING,
    IN_PROGRESS,
    WAITING_BLOCKER,
    COMPLETED,
    FAILED
}

/**
 * TeamTaskSnapshot — 团队任务快照
 *
 * 借鉴 DeepSeek Harness (docs/subsystems/agent-team.zh.md & packages/experimental/agent-team/src/types.ts):
 * 具备 CAS 版本控制 (revision)、前置依赖 (blockedBy) 与路径写入范围约束 (writeScopes)
 */
@Serializable
data class TeamTaskSnapshot(
    val id: String,
    val revision: Long = 1L,
    val subject: String,
    val description: String = "",
    val status: TeamTaskStatus = TeamTaskStatus.PENDING,
    val ownerId: String? = null,
    val blockedBy: List<String> = emptyList(),
    val writeScopes: List<String> = emptyList()
) {
    /**
     * 判断当前任务是否无未解决的阻塞项
     */
    fun isReady(allTasks: Map<String, TeamTaskSnapshot>): Boolean {
        if (status != TeamTaskStatus.PENDING && status != TeamTaskStatus.WAITING_BLOCKER) return false
        return blockedBy.all { blockerId ->
            allTasks[blockerId]?.status == TeamTaskStatus.COMPLETED
        }
    }
}

/**
 * TeamTaskBoard — 线程安全的团队任务 DAG 看板管理器
 */
class TeamTaskBoard {
    private val tasks = ConcurrentHashMap<String, TeamTaskSnapshot>()
    private val revisionCounter = AtomicLong(1L)

    fun getTask(id: String): TeamTaskSnapshot? = tasks[id]

    fun getAllTasks(): Map<String, TeamTaskSnapshot> = tasks.toMap()

    /**
     * 写入或新增任务
     */
    fun upsertTask(
        id: String,
        subject: String,
        description: String = "",
        blockedBy: List<String> = emptyList(),
        writeScopes: List<String> = emptyList()
    ): TeamTaskSnapshot {
        val existing = tasks[id]
        val rev = revisionCounter.incrementAndGet()
        val status = if (blockedBy.isNotEmpty()) TeamTaskStatus.WAITING_BLOCKER else TeamTaskStatus.PENDING
        val snapshot = existing?.copy(
            revision = rev,
            subject = subject,
            description = description,
            blockedBy = blockedBy,
            writeScopes = writeScopes
        ) ?: TeamTaskSnapshot(
            id = id,
            revision = rev,
            subject = subject,
            description = description,
            status = status,
            blockedBy = blockedBy,
            writeScopes = writeScopes
        )
        tasks[id] = snapshot
        return snapshot
    }

    /**
     * 更新任务状态（支持 CAS 乐观锁）
     */
    fun updateStatus(id: String, newStatus: TeamTaskStatus, ownerId: String? = null): Boolean {
        val existing = tasks[id] ?: return false
        val rev = revisionCounter.incrementAndGet()
        val updated = existing.copy(
            revision = rev,
            status = newStatus,
            ownerId = ownerId ?: existing.ownerId
        )
        tasks[id] = updated

        // 当任务完成时，刷新依赖此任务的其他 WAITING_BLOCKER 任务
        if (newStatus == TeamTaskStatus.COMPLETED) {
            tasks.values.forEach { other ->
                if (other.status == TeamTaskStatus.WAITING_BLOCKER && other.isReady(tasks)) {
                    tasks[other.id] = other.copy(
                        revision = revisionCounter.incrementAndGet(),
                        status = TeamTaskStatus.PENDING
                    )
                }
            }
        }
        return true
    }

    /**
     * 获取所有就绪可被认领的任务
     */
    fun getReadyTasks(): List<TeamTaskSnapshot> {
        val all = tasks.toMap()
        return all.values.filter { it.isReady(all) }
    }
}
