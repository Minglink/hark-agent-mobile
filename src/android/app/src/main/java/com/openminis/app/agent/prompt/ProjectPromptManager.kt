package com.openminis.app.agent.prompt

import java.io.File

/**
 * Discovers and manages workspace-specific project conventions and system prompt instructions.
 * Scans candidate files in precedence order:
 * 1. .hark/PROJECT_PROMPT.md
 * 2. SYSTEM.md
 * 3. HARK.md
 * 4. CLAUDE.md
 */
object ProjectPromptManager {

    /**
     * Resolves project-level instructions for a given workspace path.
     * Checks file timestamps to avoid reading unchanged files repeatedly.
     */
    fun resolveProjectPrompt(workspaceDir: File?): String? {
        val info = resolveProjectPromptInfo(workspaceDir) ?: return null
        return render(info)
    }

    internal fun resolveProjectPromptInfo(workspaceDir: File?): com.openminis.app.agent.ProjectPromptInfo? {
        if (workspaceDir?.isDirectory != true) return null
        // Retain this facade's historical precedence while sharing the parser/cache.
        for (path in listOf(".hark/PROJECT_PROMPT.md", "SYSTEM.md", "HARK.md", "CLAUDE.md")) {
            val info = com.openminis.app.agent.ProjectPromptManager.parseProjectPromptFile(File(workspaceDir, path))
            if (info != null && info.content.isNotBlank()) return info
        }
        return null
    }

    internal fun render(info: com.openminis.app.agent.ProjectPromptInfo): String {
        return "\n\n=== WORKSPACE PROJECT INSTRUCTIONS (${info.file.name}) ===\n${info.content}\n=== END WORKSPACE INSTRUCTIONS ==="
    }

    /**
     * Clears all cached prompt contents.
     */
    fun clearCache() {
        com.openminis.app.agent.ProjectPromptManager.clearCache()
    }
}
