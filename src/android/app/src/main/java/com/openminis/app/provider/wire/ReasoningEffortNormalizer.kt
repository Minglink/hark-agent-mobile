package com.openminis.app.provider.wire

import com.openminis.app.data.model.ThinkingLevel

/**
 * ReasoningEffortNormalizer — 跨厂商推理预算归一化引擎
 *
 * 借鉴 OpenCodex (src/reasoning-effort.ts) 与 Claude Code 协议架构：
 * 将应用层的统一思考标尺 (ThinkingLevel: OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA)
 * 准确映射与注入到各主流大模型厂商（Anthropic, OpenAI, DeepSeek, Gemini, Qwen）的专有 Wire 请求体中。
 */
object ReasoningEffortNormalizer {

    /**
     * 计算 Anthropic Extended Thinking 的 budget_tokens
     * 规范：Anthropic 要求 budget_tokens 必须 >= 1024 且 < max_tokens
     */
    fun resolveAnthropicBudgetTokens(level: ThinkingLevel, maxOutputTokens: Int = 16384): Int? {
        if (!level.isEnabled) return null
        val budget = when (level) {
            ThinkingLevel.OFF -> return null
            ThinkingLevel.LOW -> 1024
            ThinkingLevel.MEDIUM -> 4096
            ThinkingLevel.HIGH -> 8192
            ThinkingLevel.XHIGH -> 16384
            ThinkingLevel.MAX -> 24576
            ThinkingLevel.ULTRA -> 32768
        }
        // 确保不超过 maxOutputTokens - 1024
        val ceiling = (maxOutputTokens - 1024).coerceAtLeast(1024)
        return budget.coerceIn(1024, ceiling)
    }

    /**
     * 计算 OpenAI o1/o3-mini / GPT-5 系列的 reasoning_effort 参数
     * 规范：OpenAI 官方接受 "low", "medium", "high"
     */
    fun resolveOpenAIReasoningEffort(level: ThinkingLevel): String? {
        if (!level.isEnabled) return null
        return when (level) {
            ThinkingLevel.OFF -> null
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "high"
        }
    }

    /**
     * 计算 Google Gemini 2.0 Flash / Pro Thinking 的 thinking_budget
     * 规范：Gemini thinkingConfig 接收 0 (禁用) 或具体 token 数 (如 1024..24576)
     */
    fun resolveGeminiThinkingBudget(level: ThinkingLevel): Int? {
        if (!level.isEnabled) return 0
        return when (level) {
            ThinkingLevel.OFF -> 0
            ThinkingLevel.LOW -> 1024
            ThinkingLevel.MEDIUM -> 4096
            ThinkingLevel.HIGH -> 8192
            ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
        }
    }

    /**
     * DeepSeek-R1 / Reasoner 推荐保证的最小输出 Token 上限
     */
    fun resolveDeepSeekTokenFloor(level: ThinkingLevel, baseTokens: Int): Int {
        if (!level.isEnabled) return baseTokens
        val floor = when (level) {
            ThinkingLevel.OFF -> baseTokens
            ThinkingLevel.LOW -> 4096
            ThinkingLevel.MEDIUM -> 8192
            ThinkingLevel.HIGH -> 16384
            ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
        }
        return maxOf(baseTokens, floor)
    }
}
