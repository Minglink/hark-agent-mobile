package com.openminis.app.agent.subagent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolResultTest {
    @Test
    fun `failed edit is an error to the model and a failed persisted receipt`() {
        val failed = ToolExecutionResult("Error: original text was not found", success = false)
        val message = SubagentExecutionPolicy.toolResultMessage("edit-1", "file_edit", failed)
        val receipt = JSONArray(SubagentExecutionPolicy.persistedToolResult("edit-1", failed)).getJSONObject(0)

        assertEquals(LLMMessage.Role.USER, message.role)
        val result = message.contentParts.single() as AgentContentPart.ToolResult
        assertEquals("edit-1", result.id)
        assertEquals("file_edit", result.name)
        assertTrue(result.isError)
        assertEquals(failed.output, result.content)
        assertEquals("toolResult", receipt.getString("type"))
        assertEquals("edit-1", receipt.getJSONObject("value").getString("toolUseId"))
        assertFalse(receipt.getJSONObject("value").getBoolean("success"))
        assertEquals(failed.output, receipt.getString("content"))
    }

    @Test
    fun `successful read keeps existing tool result protocol`() {
        val success = ToolExecutionResult("File contents", success = true)
        val message = SubagentExecutionPolicy.toolResultMessage("read-1", "file_read", success)
        val receipt = JSONArray(SubagentExecutionPolicy.persistedToolResult("read-1", success)).getJSONObject(0)

        assertFalse((message.contentParts.single() as AgentContentPart.ToolResult).isError)
        assertTrue(receipt.getJSONObject("value").getBoolean("success"))
        assertEquals("read-1", receipt.getString("tool_use_id"))
        assertEquals(success.output, receipt.getJSONObject("value").getString("output"))
    }

    @Test
    fun `persisted errors remain bounded without truncating the live tool result`() {
        val failed = ToolExecutionResult("Failed: " + "details ".repeat(1000), success = false)
        val message = SubagentExecutionPolicy.toolResultMessage("write-1", "file_write", failed)
        val receipt = JSONArray(SubagentExecutionPolicy.persistedToolResult("write-1", failed)).getJSONObject(0)

        assertEquals(failed.output, (message.contentParts.single() as AgentContentPart.ToolResult).content)
        assertEquals(1000, receipt.getString("content").length)
        assertEquals(1000, receipt.getJSONObject("value").getString("output").length)
        assertFalse(receipt.getJSONObject("value").getBoolean("success"))
    }

    @Test
    fun `normal tool execution accumulates a single batch while retaining partial progress`() {
        val requested = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse("a", "file_read", JSONObject()),
                AgentContentPart.ToolUse("b", "file_edit", JSONObject()),
            ),
        )
        val history = mutableListOf(requested)

        SubagentExecutionPolicy.appendToolResultToHistory(history, "a", "file_read", ToolExecutionResult("Read contents", true))
        assertEquals(2, history.size)
        assertEquals("a", (history[1].contentParts.single() as AgentContentPart.ToolResult).id)

        val interrupted = SubagentExecutionPolicy.historyForResume(history, "Continue")
        assertEquals(listOf("a", "b"), interrupted[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id })
        assertFalse((interrupted[1].contentParts[0] as AgentContentPart.ToolResult).isError)
        assertTrue((interrupted[1].contentParts[1] as AgentContentPart.ToolResult).isError)
        assertEquals(1, history[1].contentParts.size)

        SubagentExecutionPolicy.appendToolResultToHistory(history, "b", "file_edit", ToolExecutionResult("Text not found", false))
        assertEquals(2, history.size)
        val batch = history[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals(listOf("a", "b"), batch.map { it.id })
        assertFalse(batch[0].isError)
        assertTrue(batch[1].isError)
    }

    @Test
    fun `a new assistant turn starts its own tool receipt batch`() {
        fun assistant(id: String) = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(AgentContentPart.ToolUse(id, "file_read", JSONObject())),
        )
        val history = mutableListOf(assistant("a"))
        SubagentExecutionPolicy.appendToolResultToHistory(history, "a", "file_read", ToolExecutionResult("First", true))
        history += assistant("b")
        SubagentExecutionPolicy.appendToolResultToHistory(history, "b", "file_read", ToolExecutionResult("Second", true))

        assertEquals(4, history.size)
        assertEquals("a", (history[1].contentParts.single() as AgentContentPart.ToolResult).id)
        assertEquals("b", (history[3].contentParts.single() as AgentContentPart.ToolResult).id)
    }
}
