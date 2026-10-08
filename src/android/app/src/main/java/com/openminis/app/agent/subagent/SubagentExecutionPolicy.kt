package com.openminis.app.agent.subagent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/** Result rules shared by the delegate loop and its persisted tool receipts. */
internal object SubagentExecutionPolicy {
    fun historyForResume(history: List<LLMMessage>, intervention: String): List<LLMMessage> {
        val repaired = mutableListOf<LLMMessage>()
        val pending = linkedMapOf<String, AgentContentPart.ToolUse>()
        val received = linkedMapOf<String, AgentContentPart.ToolResult>()

        fun completePending() {
            if (pending.isEmpty()) return
            val receipts = pending.values.map { call ->
                received[call.id] ?: AgentContentPart.ToolResult(
                    id = call.id,
                    name = call.name,
                    content = "Execution was interrupted before a confirmed tool result was recorded. " +
                        "Check the current state before retrying this action; it may already have taken effect.",
                    isError = true,
                )
            }
            // Anthropic sanitizes before merging consecutive user messages.
            // Every receipt for one assistant batch must already be in the
            // immediately following single user message at that boundary.
            repaired += toolResultBatch(receipts)
            pending.clear()
            received.clear()
        }

        for (message in history) {
            if (message.role == LLMMessage.Role.ASSISTANT) {
                completePending()
                repaired += message
                message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                    .forEach { pending[it.id] = it }
            } else {
                if (pending.isEmpty()) {
                    repaired += message
                    continue
                }
                val receipts = message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                receipts.filter { it.id in pending }.forEach { received[it.id] = it }
                val ordinaryParts = message.contentParts.filter { it !is AgentContentPart.ToolResult }
                // Missing results must precede ordinary user text, including
                // prior interventions already present in a reloaded history.
                if (receipts.isEmpty() || ordinaryParts.isNotEmpty() ||
                    message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty()
                ) {
                    completePending()
                    repaired += if (receipts.isEmpty()) message else message.copy(contentParts = ordinaryParts)
                }
            }
        }
        completePending()
        repaired += LLMMessage(
            role = LLMMessage.Role.USER,
            content = "【用户人工干预指令】: $intervention",
        )
        return repaired
    }

    /** Update one batch after each tool finishes, so cancellation retains partial progress. */
    fun appendToolResultToHistory(
        history: MutableList<LLMMessage>,
        id: String,
        name: String,
        result: ToolExecutionResult,
    ) {
        val single = toolResultMessage(id, name, result)
        val receipt = single.contentParts.single() as AgentContentPart.ToolResult
        val last = history.lastOrNull()
        if (last?.role == LLMMessage.Role.USER && last.contentParts.isNotEmpty() &&
            last.contentParts.all { it is AgentContentPart.ToolResult }
        ) {
            history[history.lastIndex] = toolResultBatch(
                last.contentParts.filterIsInstance<AgentContentPart.ToolResult>() + receipt,
            )
        } else {
            history += single
        }
    }

    private fun toolResultBatch(receipts: List<AgentContentPart.ToolResult>): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER,
        content = receipts.joinToString("\n") { it.content },
        contentParts = receipts,
    )

    fun requireFollowUpBudget(turnsUsed: Int, maxTurns: Int) {
        check(turnsUsed < maxTurns) {
            "子代理已达到 $maxTurns 轮执行上限，最后一轮仍需继续处理工具结果，任务尚未完成。"
        }
    }

    fun toolResultMessage(id: String, name: String, result: ToolExecutionResult): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER,
        content = result.output,
        contentParts = listOf(
            AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = result.output,
                isError = !result.success,
            )
        ),
    )

    fun persistedToolResult(id: String, result: ToolExecutionResult): String = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "toolResult")
            put("value", JSONObject().apply {
                put("toolUseId", id)
                put("output", result.output.take(1000))
                put("success", result.success)
            })
            put("tool_use_id", id)
            put("content", result.output.take(1000))
        })
    }.toString()
}
