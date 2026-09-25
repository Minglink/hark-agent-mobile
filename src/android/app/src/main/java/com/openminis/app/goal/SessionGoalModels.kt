package com.openminis.app.goal

import kotlinx.serialization.Serializable

enum class GoalStatus {
    PLANNING,      // 规划与探索阶段
    IN_PROGRESS,   // 推进执行中
    PAUSED,        // 用户主动暂停
    COMPLETED,     // 全部里程碑达成
    ABORTED        // 终止或放弃
}

enum class MilestoneStatus {
    PENDING,       // 待处理
    IN_PROGRESS,   // 当前正在执行
    COMPLETED,     // 已完成
    FAILED         // 执行失败或阻塞
}

@Serializable
data class GoalMilestone(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: MilestoneStatus = MilestoneStatus.PENDING,
    val isDestructive: Boolean = false,
    val completedAt: Long? = null,
)

@Serializable
data class SessionGoal(
    val id: String,
    val sessionId: String,
    val goalText: String,
    val status: GoalStatus = GoalStatus.IN_PROGRESS,
    val milestones: List<GoalMilestone> = emptyList(),
    val currentMilestoneIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalMilestones: Int
        get() = milestones.size

    val completedMilestones: Int
        get() = milestones.count { it.status == MilestoneStatus.COMPLETED }

    val currentMilestone: GoalMilestone?
        get() = milestones.getOrNull(currentMilestoneIndex)
            ?: milestones.firstOrNull { it.status == MilestoneStatus.IN_PROGRESS }
            ?: milestones.firstOrNull { it.status == MilestoneStatus.PENDING }
}
