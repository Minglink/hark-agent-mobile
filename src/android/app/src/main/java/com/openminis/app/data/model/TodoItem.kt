package com.openminis.app.data.model

data class TodoItem(
    val id: String,
    val content: String,
    val status: String, // "pending", "in_progress", "completed"
    val priority: String = "medium", // "high", "medium", "low"
) {
    val isCompleted: Boolean get() = status == "completed"
    val isInProgress: Boolean get() = status == "in_progress"
    val isPending: Boolean get() = status == "pending"
}
