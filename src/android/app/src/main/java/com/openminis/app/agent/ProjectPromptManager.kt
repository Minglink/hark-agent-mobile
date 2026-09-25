package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * ProjectPromptMode — 项目专属提示词与全局 SYSTEM.md 的级联模式
 */
enum class ProjectPromptMode {
    /** 增量追加在全局提示词之后（默认） */
    APPEND,
    /** 完全覆盖全局 SYSTEM.md，以本项目工程规约为绝对主导 */
    OVERRIDE,
}

data class ProjectPromptInfo(
    val file: File,
    val mode: ProjectPromptMode,
    val content: String,
)

/**
 * ProjectPromptManager — 项目级系统提示词与工程规约管理器
 *
 * 核心设计：
 * 1. 自动探测项目工作区根目录下的规约文件：
 *    - `.hark/PROJECT_PROMPT.md` (第一优先级专属文件)
 *    - `HARK.md` (项目级标准规则)
 *    - `CLAUDE.md` / `.claude/CLAUDE.md` (业内常见规范兼容)
 * 2. 支持 YAML Frontmatter 声明 `mode: "append" | "override"`；
 * 3. 严格遵循分级权威矩阵：在 Tier 1 (工程域) 注入，绝不破坏 Tier 0 (平台工具协议)。
 */
object ProjectPromptManager {

    private const val TAG = "ProjectPromptManager"
    const val MAX_PROJECT_PROMPT_CHARS = 100_000

    private val CANDIDATE_PATHS = listOf(
        ".hark/PROJECT_PROMPT.md",
        ".hark/RULES.md",
        "HARK.md",
        "CLAUDE.md",
        ".claude/CLAUDE.md"
    )

    /**
     * 探测并加载指定项目工作区目录下的工程规约
     * @param workspaceDir 项目绑定的 Host 目录 (例如 projectHostDir)
     */
    fun loadProjectPrompt(workspaceDir: File?): ProjectPromptInfo? {
        if (workspaceDir == null || !workspaceDir.exists() || !workspaceDir.isDirectory) {
            return null
        }

        for (relPath in CANDIDATE_PATHS) {
            val candidate = File(workspaceDir, relPath)
            if (candidate.exists() && candidate.isFile && candidate.canRead()) {
                val parsed = parseProjectPromptFile(candidate)
                if (parsed != null && parsed.content.isNotBlank()) {
                    return parsed
                }
            }
        }
        return null
    }

    /**
     * 解析工程规约文件，提取 mode 与主体内容
     */
    fun parseProjectPromptFile(file: File): ProjectPromptInfo? {
        return try {
            val raw = file.readText(Charsets.UTF_8).trim()
            if (raw.isEmpty()) return null

            var mode = ProjectPromptMode.APPEND
            var body = raw

            val trimmedLeading = raw.trimStart('\uFEFF').dropWhile { it == '\n' || it == '\r' || it == ' ' }
            if (trimmedLeading.startsWith("---")) {
                val lines = trimmedLeading.split("\n")
                if (lines.firstOrNull()?.trim() == "---") {
                    val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
                    if (closeIdx >= 0) {
                        val absCloseIdx = closeIdx + 1
                        val frontmatter = lines.subList(1, absCloseIdx)
                        val bodyLines = if (absCloseIdx + 1 <= lines.size) lines.subList(absCloseIdx + 1, lines.size) else emptyList()
                        body = bodyLines.joinToString("\n").trim()

                        for (line in frontmatter) {
                            val trimmed = line.trim()
                            val colon = trimmed.indexOf(':')
                            if (colon > 0) {
                                val k = trimmed.substring(0, colon).trim().lowercase()
                                var v = trimmed.substring(colon + 1).trim()
                                if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) {
                                    v = v.substring(1, v.length - 1)
                                }
                                if (k == "mode" && v.lowercase() == "override") {
                                    mode = ProjectPromptMode.OVERRIDE
                                }
                            }
                        }
                    }
                }
            }

            if (body.length > MAX_PROJECT_PROMPT_CHARS) {
                body = body.take(MAX_PROJECT_PROMPT_CHARS) + "\n...[truncated]"
            }

            ProjectPromptInfo(
                file = file,
                mode = mode,
                content = body
            )
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Failed to parse project prompt from ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * 生成格式化包装块供 System Prompt 组装使用
     */
    fun renderPromptBlock(info: ProjectPromptInfo, projectName: String): String {
        return buildString {
            append("<project_scope name=\"").append(projectName).append("\" file=\"").append(info.file.name).append("\">\n")
            append("# Project Engineering Directives & Standards (Highest Project-Level Priority)\n")
            append("You are currently working inside project '").append(projectName).append("'. ")
            append("Strictly follow the project architecture, dependencies, conventions, and style rules below:\n\n")
            append(info.content)
            append("\n</project_scope>")
        }
    }

    /**
     * 写入或更新项目专属提示词文件 (`.hark/PROJECT_PROMPT.md`)
     */
    fun saveProjectPrompt(workspaceDir: File, content: String, mode: ProjectPromptMode = ProjectPromptMode.APPEND): Boolean {
        return try {
            val targetDir = File(workspaceDir, ".hark").also { it.mkdirs() }
            val targetFile = File(targetDir, "PROJECT_PROMPT.md")
            val modeStr = if (mode == ProjectPromptMode.OVERRIDE) "override" else "append"
            val text = "---\nmode: \"$modeStr\"\n---\n\n" + content.trim() + "\n"
            targetFile.writeText(text, Charsets.UTF_8)
            AppLogger.info(TAG, "Saved project prompt to ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to save project prompt: ${e.message}")
            false
        }
    }
}
