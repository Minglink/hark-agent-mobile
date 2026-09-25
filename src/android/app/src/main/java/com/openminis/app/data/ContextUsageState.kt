package com.openminis.app.data

/**
 * Snapshot of the current conversation's token consumption relative to the
 * model's context window. Published by [com.openminis.app.ui.chat.ChatViewModel]
 * and consumed by [com.openminis.app.ui.chat.ContextUsageIndicator].
 */
data class ContextUsageState(
    val usedTokens: Int = 0,
    val windowTokens: Int = 128_000,
    val ratio: Float = 0f,
    val level: Level = Level.NORMAL,
    val isCompacting: Boolean = false,
) {
    enum class Level {
        /** Under 70% capacity: safe, minimal token pressure (Green) */
        NORMAL,
        /** 70% - 85% capacity: approaching auto-compaction boundary (Amber) */
        WARNING,
        /** >= 85% capacity: compaction required (Red / Pulsing) */
        CRITICAL,
    }

    val percentage: Int
        get() = (ratio * 100).toInt().coerceIn(0, 100)

    val remainingTokens: Int
        get() = (windowTokens - usedTokens).coerceAtLeast(0)

    companion object {
        fun compute(usedTokens: Int, windowTokens: Int, isCompacting: Boolean = false): ContextUsageState {
            val safeWindow = windowTokens.coerceAtLeast(1)
            val ratio = (usedTokens.toFloat() / safeWindow).coerceIn(0f, 1f)
            val level = when {
                ratio >= 0.85f -> Level.CRITICAL
                ratio >= 0.70f -> Level.WARNING
                else -> Level.NORMAL
            }
            return ContextUsageState(
                usedTokens = usedTokens,
                windowTokens = safeWindow,
                ratio = ratio,
                level = level,
                isCompacting = isCompacting,
            )
        }
    }
}
