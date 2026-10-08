package com.openminis.app.agent

/** Pure, deterministic disclosure policy; skill bodies are loaded only for an explicit activation. */
internal object SkillPromptBuilder {
    data class Entry(
        val id: String,
        val name: String,
        val description: String,
        val updatedAt: Long,
        val useCount: Double,
        val bundled: Boolean,
    )

    private const val MAX_SKILLS = 20
    private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000

    fun catalog(entries: List<Entry>, now: Long): String? {
        if (entries.isEmpty()) return null
        val recentOrder = compareByDescending<Entry> { it.updatedAt }.thenBy { it.id }
        val selected = if (entries.size <= MAX_SKILLS) entries.sortedWith(recentOrder) else {
            val picked = linkedMapOf<String, Entry>()
            entries.filter { it.bundled }.sortedWith(recentOrder).take(MAX_SKILLS)
                .forEach { picked[it.id] = it }
            entries.filter { it.updatedAt > now - RECENT_WINDOW_MS && it.id !in picked }
                .sortedWith(recentOrder).take(minOf(10, MAX_SKILLS - picked.size))
                .forEach { picked[it.id] = it }
            entries.filter { it.id !in picked }
                .sortedWith(compareByDescending<Entry> { it.useCount }.thenBy { it.id })
                .take(MAX_SKILLS - picked.size).forEach { picked[it.id] = it }
            picked.values.toList()
        }
        return buildString {
            append("Skills:\nReusable instruction sets stored at /var/hark/skills/<name>/SKILL.md. ")
            append("Read the SKILL.md file before using a skill unless its full instructions are already loaded in ACTIVE_TARGETED_SKILL. ")
            append("Reuse loaded instructions for this task; read referenced resources only when needed.\n\n<available_skills>\n")
            for (skill in selected) {
                val desc = skill.description.take(200) + if (skill.description.length > 200) "…" else ""
                append("  <skill>\n    <name>").append(xml(skill.name)).append("</name>\n")
                append("    <description>").append(xml(desc)).append("</description>\n")
                append("    <path>").append(xml("/var/hark/skills/${skill.id}/SKILL.md")).append("</path>\n  </skill>\n")
            }
            append("</available_skills>")
            val selectedIds = selected.mapTo(hashSetOf()) { it.id }
            val omitted = entries.filter { it.id !in selectedIds }.sortedBy { it.id }
            if (omitted.isNotEmpty()) {
                append("\n\n").append(omitted.size).append(" more skills not shown above: ")
                append(omitted.take(100 - selected.size).joinToString(", ") { it.name })
                append(". List /var/hark/skills/ or grep to search all.")
            }
        }
    }

    fun targeted(name: String, body: String, path: String): String? {
        if (body.isBlank()) return null
        return buildString {
            append("\n\n=== [ACTIVE_TARGETED_SKILL: ").append(name).append("] ===\n")
            append("The user explicitly activated this skill via /skill for this task. ")
            append("Follow its procedures within the active system and project instructions.\n")
            append("Skill file: ").append(path).append('\n')
            if (body.length <= 25_000) {
                append("The full procedure is loaded below; do not read this unchanged SKILL.md again. ")
                append("Read its referenced files only when needed.\n\n").append(body)
            } else {
                // A partial procedure can silently omit mandatory steps near its end.
                append("The procedure is too large to inline. Read the full SKILL.md with file_read ")
                append("(including subsequent ranges) before applying it; no partial procedure is injected.")
            }
            append("\n=== [END_ACTIVE_TARGETED_SKILL] ===\n")
        }
    }

    private fun xml(text: String): String = text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
