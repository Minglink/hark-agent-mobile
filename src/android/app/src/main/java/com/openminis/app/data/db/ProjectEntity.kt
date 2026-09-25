package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-project-management] A top-level project container. Projects are optional
 * organizational units that sit ABOVE folders (groups) — a project can contain
 * multiple folders and/or sessions directly.
 *
 * Design principles (mirroring deepseek-harness workspace package):
 * - Deleting a project NEVER deletes folders or sessions (soft reference only).
 * - `linuxPath` is an optional binding to an Alpine sandbox directory.
 *   When set, new sessions created under this project get that path as cwd.
 * - `project_id` on FolderEntity/ChatSessionEntity is nullable and carries
 *   NO @ForeignKey — orphan references render as "no project" instead of
 *   failing a constraint, matching the same pattern as folder_id.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    /**
     * Optional Alpine sandbox path (e.g. "/var/hark/myproject").
     * When non-null, sessions created inside this project start with this cwd.
     * A missing or moved directory never mutates the record — the directory
     * may only be temporarily unavailable.
     */
    @ColumnInfo(name = "linux_path") val linuxPath: String? = null,
    val icon: String? = null,
    val color: String? = null,
    /** Non-null = pinned to the top of the project list. Milliseconds epoch. */
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    /**
     * Reserved for drag-reorder. Always 0 today; kept so a later reorder
     * feature needs no migration.
     */
    @ColumnInfo(name = "sort_index") val sortIndex: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val isPinned: Boolean get() = pinnedAt != null

    companion object {
        const val DESC_MAX_CHARS = 100
    }
}
