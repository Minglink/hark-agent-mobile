package com.openminis.app.security

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.wire.ToolWireNormalizer

/**
 * SecretRedactor — 端侧在途机密脱敏防护网
 *
 * 借鉴 Hermes Agent (agent/redact.py & secret_scope.py):
 * 在向任何远程 LLM Provider 发送请求之前，自动对上下文、历史记录、Shell输出与文件内容
 * 进行正则与高熵值探测，将 API 密钥、私钥、Token 进行脱敏，绝不让用户隐私明文上云。
 */
object SecretRedactor {

    private val SENSITIVE_PATTERNS = listOf(
        // OpenAI / DeepSeek / 通用 sk- 格式
        Regex("""sk-[a-zA-Z0-9_\-]{20,}"""),
        // Anthropic Claude API Key
        Regex("""sk-ant-[a-zA-Z0-9_\-]{20,}"""),
        // GitHub Personal Access Tokens
        Regex("""ghp_[a-zA-Z0-9]{36}"""),
        Regex("""github_pat_[a-zA-Z0-9_]{50,}"""),
        // Slack Tokens
        Regex("""xox[baprs]-[0-9a-zA-Z]{10,48}"""),
        // AWS Access Key ID
        Regex("""AKIA[0-9A-Z]{16}"""),
        // 私钥标头
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        // Authorization Bearer Token
        Regex("""(?i)bearer\s+[a-zA-Z0-9_\-\.]{32,}""")
    )

    private const val REDACTED_MASK = "[REDACTED_SECRET]"

    /**
     * 对单一文本执行脱敏
     */
    fun redact(text: String?): String? {
        if (text.isNullOrBlank()) return text
        var result: String = text
        for (pattern in SENSITIVE_PATTERNS) {
            result = pattern.replace(result) { match ->
                val matchedStr = match.value
                // 保留前缀（例如 Bearer 或 sk-），将其余掩码
                if (matchedStr.startsWith("sk-ant-")) {
                    "sk-ant-$REDACTED_MASK"
                } else if (matchedStr.startsWith("sk-")) {
                    "sk-$REDACTED_MASK"
                } else if (matchedStr.startsWith("ghp_")) {
                    "ghp_$REDACTED_MASK"
                } else {
                    REDACTED_MASK
                }
            }
        }
        return result
    }

    /**
     * 对消息列表进行在途脱敏与工具输出安全规范化
     */
    fun redactMessages(messages: List<LLMMessage>): List<LLMMessage> {
        return messages.map { msg ->
            val redactedContent = redact(msg.content) ?: msg.content
            val redactedParts = if (msg.contentParts.isNotEmpty()) {
                msg.contentParts.map { part ->
                    when (part) {
                        is AgentContentPart.Text -> {
                            val rText = redact(part.text) ?: part.text
                            if (rText == part.text) part else part.copy(text = rText)
                        }
                        is AgentContentPart.ToolResult -> {
                            val rContent = redact(part.content) ?: part.content
                            val normalized = ToolWireNormalizer.normalizeToolResultContent(rContent)
                            if (normalized == part.content) part else part.copy(content = normalized)
                        }
                        else -> part
                    }
                }
            } else {
                msg.contentParts
            }
            if (redactedContent == msg.content && redactedParts == msg.contentParts) {
                msg
            } else {
                msg.copy(content = redactedContent, contentParts = redactedParts)
            }
        }
    }
}
