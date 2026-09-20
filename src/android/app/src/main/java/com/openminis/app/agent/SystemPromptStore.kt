package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-system-md] Highest-priority custom system prompt (SYSTEM.md), mirroring
 * the SOUL.md file shape (YAML frontmatter + Markdown body) but with a
 * deliberately different contract:
 *
 *  - SOUL.md is *personality* — the write path rejects prompt-injection
 *    patterns and scrubInjections drops matching lines at prompt-build time.
 *  - SYSTEM.md is the user's *own system prompt* — the user's device, the
 *    user's API key. Content is injected VERBATIM: no scrubbing, no
 *    truncation, no "defer to user" demotion. The only guards are a
 *    generous char limit (foolproofing, not filtering) and the OFF mode.
 *
 * Injection placement is chosen by [SystemPromptMode]:
 *  - OFF      → the built-in prompt is used unchanged (byte-identical to
 *               the pre-feature build).
 *  - PREPEND  → the custom block goes at the very top, before the identity
 *               sentence — read first by the model.
 *  - SUFFIX   → the custom block goes after the built-in identity +
 *               tool-guidance layers but BEFORE the dynamic fragments
 *               (skills / MCP / memory / runtime context), so the static
 *               prefix stays cache-stable.
 *
 * In every active mode the custom block is labeled with an explicit
 * priority statement and the built-in layers are fully preserved — tool
 * discoverability is never traded away for prompt control.
 */

enum class SystemPromptMode {
    /** Custom prompt inactive — built-in prompt unchanged. */
    OFF,
    /** Inject at the very top of the system prompt. */
    PREPEND,
    /** Inject after the built-in layers, before dynamic fragments. */
    SUFFIX,
}

data class SystemPromptFile(
    val mode: SystemPromptMode,
    val body: String,
)

object SystemPromptStore {

    private const val TAG = "SystemPromptStore"
    private const val FILE_NAME = "SYSTEM.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"

    /**
     * Foolproofing cap only — NOT a content filter. 100,000 characters is
     * far above any hand-authored prompt; the point is to stop a runaway
     * import (or an agent file_write loop) from blowing past the model's
     * context window. Over-limit bodies are ignored at prompt-build time
     * and rejected by the Settings save/import paths.
     */
    const val BODY_CHAR_LIMIT: Int = 100_000

    /** Inactive-by-default content used for first-run seeding and Restore Default. */
    val DEFAULT: SystemPromptFile = SystemPromptFile(SystemPromptMode.OFF, "")

    val DEFAULT_CONTENT: String = serialize(DEFAULT)

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    /** Create SYSTEM.md with [DEFAULT_CONTENT] iff it does not exist yet. */
    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            AppLogger.info(TAG, "seeded SYSTEM.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ensureExists failed: ${t.message}")
        }
    }

    /**
     * Read + parse SYSTEM.md. Returns null when the file is missing or
     * unreadable. A file with no frontmatter parses as mode=OFF with the
     * whole content as body (preserves hand-written files round-trip).
     */
    fun load(context: Context): SystemPromptFile? {
        val file = fileLocation(context)
        if (!file.exists()) return null
        return try {
            parse(file.readText())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "SYSTEM.md load failed: ${t.message}")
            null
        }
    }

    /** Atomic write through a `.tmp` sibling, then rename. */
    fun save(context: Context, file: SystemPromptFile) {
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val text = serialize(file)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    fun isOverLimit(body: String): Boolean = body.trim().length > BODY_CHAR_LIMIT

    fun parseMode(raw: String?): SystemPromptMode = when (raw?.trim()?.lowercase()) {
        "prepend", "top" -> SystemPromptMode.PREPEND
        "suffix", "bottom" -> SystemPromptMode.SUFFIX
        else -> SystemPromptMode.OFF
    }

    /** Minimal frontmatter parser — only the `mode` key, `key: "value"` lines. */
    fun parse(source: String): SystemPromptFile {
        val trimmedLeading = source.dropWhile { it == '\n' || it == '\r' }
        if (!trimmedLeading.startsWith("---")) {
            return SystemPromptFile(SystemPromptMode.OFF, source)
        }
        val lines = trimmedLeading.split("\n")
        if (lines.firstOrNull()?.trim() != "---") {
            return SystemPromptFile(SystemPromptMode.OFF, source)
        }
        val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closeIdx < 0) return SystemPromptFile(SystemPromptMode.OFF, source)
        val absCloseIdx = closeIdx + 1
        val frontmatter = lines.subList(1, absCloseIdx)
        val bodyLines = if (absCloseIdx + 1 <= lines.size) lines.subList(absCloseIdx + 1, lines.size) else emptyList()
        val body = bodyLines.joinToString("\n").dropWhile { it == '\n' || it == '\r' }

        var mode = SystemPromptMode.OFF
        for (rawLine in frontmatter) {
            val line = rawLine.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            if (key == "mode") mode = parseMode(value)
        }
        return SystemPromptFile(mode, body)
    }

    fun serialize(file: SystemPromptFile): String {
        val modeStr = when (file.mode) {
            SystemPromptMode.OFF -> "off"
            SystemPromptMode.PREPEND -> "prepend"
            SystemPromptMode.SUFFIX -> "suffix"
        }
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("mode: \"").append(modeStr).append("\"\n")
        sb.append("---\n\n")
        sb.append(file.body)
        if (!sb.endsWith("\n")) sb.append("\n")
        return sb.toString()
    }

    /**
     * Render the labeled injection block for an active, non-empty,
     * within-limit custom prompt; null otherwise (OFF / empty / over-limit
     * are all no-ops so the built-in prompt is used unchanged).
     *
     * The label names the priority relationship explicitly and points at
     * where the default guidance sits ("below" for PREPEND, "above" for
     * SUFFIX) — no "defer to the user's latest message" demotion: this
     * block IS the user's standing instruction layer.
     */
    fun injectionBlock(file: SystemPromptFile): String? {
        if (file.mode == SystemPromptMode.OFF) return null
        val body = file.body.trim()
        if (body.isEmpty()) return null
        if (isOverLimit(body)) return null
        val defaultGuidancePosition = if (file.mode == SystemPromptMode.PREPEND) "below" else "above"
        return "Custom system prompt (user-authored — highest priority. Where this conflicts with the default guidance $defaultGuidancePosition, follow this. All tool/skill/memory capabilities described there remain available and should still be used):\n" +
            body
    }
}
