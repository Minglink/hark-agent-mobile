package com.openminis.app.provider.wire

/**
 * ToolWireNormalizer — 工具调用与返回协议垫片
 *
 * 借鉴 OpenCodex (src/adapters/exec-tool-result-normalize.ts & tool-call-id.ts):
 * 解决不同 LLM 平台对工具调用结果格式要求严苛导致报错崩溃的问题。
 */
object ToolWireNormalizer {

    private const val EMPTY_TOOL_FALLBACK = "(empty output)"
    private const val MAX_TOOL_OUTPUT_CHARS = 120_000

    /**
     * 规范化工具执行输出文本
     * 1. 空或全空白输出 -> 自动垫入 "(empty output)"，防止部分模型（如 Qwen/DeepSeek）报错
     * 2. 超长输出截断保护 -> 移动端避免因极端大文件导致 OOM 或 Context 溢出
     */
    fun normalizeToolResultContent(rawOutput: String?): String {
        if (rawOutput.isNullOrBlank()) {
            return EMPTY_TOOL_FALLBACK
        }
        if (rawOutput.length > MAX_TOOL_OUTPUT_CHARS) {
            val head = rawOutput.substring(0, MAX_TOOL_OUTPUT_CHARS / 2)
            val tail = rawOutput.substring(rawOutput.length - (MAX_TOOL_OUTPUT_CHARS / 2))
            val omitted = rawOutput.length - MAX_TOOL_OUTPUT_CHARS
            return "$head\n\n... [Hark Wire Guard: Omitted $omitted characters due to mobile context safety] ...\n\n$tail"
        }
        return rawOutput
    }

    /**
     * 规范化 Tool Call ID
     * 部分模型（如 Gemini / Bedrock）要求 ID 只能包含字母、数字和下划线
     */
    fun normalizeToolCallId(id: String): String {
        val sanitized = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (sanitized.length < 4) {
            "call_${sanitized}_safe"
        } else {
            sanitized
        }
    }
}
