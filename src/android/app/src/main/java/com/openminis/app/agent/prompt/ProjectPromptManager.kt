package com.openminis.app.agent.prompt

import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers and manages workspace-specific project conventions and system prompt instructions.
 * Scans candidate files in precedence order:
 * 1. .hark/PROJECT_PROMPT.md
 * 2. SYSTEM.md
 * 3. HARK.md
 * 4. CLAUDE.md
 */
object ProjectPromptManager {

    private const val TAG = "ProjectPromptManager"

    private val CANDIDATE_FILES = listOf(
        ".hark/PROJECT_PROMPT.md",
        "SYSTEM.md",
        "HARK.md",
        "CLAUDE.md"
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Resolves project-level instructions for a given workspace path.
     * Checks file timestamps to avoid reading unchanged files repeatedly.
     */
    fun resolveProjectPrompt(workspaceDir: File?): String? {
        if (workspaceDir == null || !workspaceDir.exists() || !workspaceDir.isDirectory) {
            return null
        }

        for (relPath in CANDIDATE_FILES) {
            val candidate = File(workspaceDir, relPath)
            if (candidate.exists() && candidate.isFile && candidate.canRead()) {
                val lastModified = candidate.lastModified()
                val cached = cache[candidate.absolutePath]
                if (cached != null && cached.first == lastModified) {
                    return cached.second
                }

                try {
                    val content = candidate.readText().trim()
                    if (content.isNotEmpty()) {
                        val formatted = "\n\n=== WORKSPACE PROJECT INSTRUCTIONS (${candidate.name}) ===\n$content\n=== END WORKSPACE INSTRUCTIONS ==="
                        cache[candidate.absolutePath] = Pair(lastModified, formatted)
                        AppLogger.info(TAG, "Loaded project prompt from ${candidate.absolutePath} (${content.length} chars)")
                        return formatted
                    }
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "Failed reading project prompt at ${candidate.absolutePath}: ${e.message}")
                }
            }
        }

        return null
    }

    /**
     * Clears all cached prompt contents.
     */
    fun clearCache() {
        cache.clear()
    }
}
