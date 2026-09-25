package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-android-session-grouping] The `folder_id` index is declared here so the
 * entity and MIGRATION_10_11 agree — Room validates the live schema against
 * the entity on open, and an index present in one but not the other aborts
 * startup with an IllegalStateException.
 *
 * Non-unique on purpose: many sessions share one group.
 */
@Entity(
    tableName = "sessions",
    indices = [
        androidx.room.Index(value = ["folder_id"], name = "index_sessions_folder_id"),
        androidx.room.Index(value = ["project_id"], name = "index_sessions_project_id"),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "updated_at") val updatedAt: Long,        // milliseconds
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "source") val source: String? = null,             // e.g. "shortcut", "share", "fork:<parent_session_id>"
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,     // 1=on, 0=off
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,          // milliseconds, null=not pinned
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,             // message edit counter
    // T239: per-session thinking-mode override. null = unset (use the
    // current model/group default — i.e. existing pre-T239 behaviour, which
    // is OFF on Android today). Non-null is one of ThinkingLevel.name
    // ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH") and represents an explicit user
    // choice that survives cold-start.
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    /**
     * [T-android-session-grouping] Group membership. NULL = ungrouped.
     */
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    /**
     * [T-project-management] Direct project membership. NULL = not assigned
     * to any project directly. Sessions can also inherit a project via their folder.
     */
    @ColumnInfo(name = "project_id") val projectId: String? = null,
)

val ChatSessionEntity.isSubagentSession: Boolean
    get() = source?.startsWith("subagent:") == true

val ChatSessionEntity.parentSessionId: String?
    get() = if (isSubagentSession) source?.removePrefix("subagent:") else null

val ChatSessionEntity.isForkSession: Boolean
    get() = source?.startsWith("fork:") == true

val ChatSessionEntity.forkParentSessionId: String?
    get() = if (isForkSession) source?.removePrefix("fork:") else null
