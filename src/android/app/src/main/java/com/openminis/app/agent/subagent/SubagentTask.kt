package com.openminis.app.agent.subagent

import kotlinx.serialization.Serializable

enum class SubagentStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

@Serializable
data class SubagentTask(
    val id: String,
    val parentSessionId: String,
    val title: String,
    val role: String,
    val prompt: String,
    val status: SubagentStatus = SubagentStatus.PENDING,
    val output: String = "",
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null
)
