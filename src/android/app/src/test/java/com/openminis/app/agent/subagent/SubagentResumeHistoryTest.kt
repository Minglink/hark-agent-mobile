package com.openminis.app.agent.subagent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentResumeHistoryTest {
    private fun toolUse(id: String) = AgentContentPart.ToolUse(id, "file_edit", JSONObject())
    private fun assistant(vararg calls: AgentContentPart.ToolUse) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "Editing files",
        contentParts = calls.toList(),
    )
    private fun receipt(id: String, isError: Boolean = false) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "Recorded result",
        contentParts = listOf(AgentContentPart.ToolResult(id, "file_edit", "Recorded result", isError)),
    )
    private fun results(message: LLMMessage) = message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()

    @Test
    fun `cancelled tool batch receives error results before the new intervention`() {
        val requested = assistant(toolUse("a"), toolUse("b"))
        val repaired = SubagentExecutionPolicy.historyForResume(listOf(requested), "Continue safely")

        assertSame(requested, repaired[0])
        assertEquals(LLMMessage.Role.USER, repaired[1].role)
        assertEquals(listOf("a", "b"), results(repaired[1]).map { it.id })
        assertTrue(results(repaired[1]).all { it.isError })
        assertTrue(results(repaired[1]).all { it.content.contains("may already have taken effect") })
        assertEquals("【用户人工干预指令】: Continue safely", repaired[2].content)
    }

    @Test
    fun `partially completed batch preserves its receipt and fills only the missing id`() {
        val requested = assistant(toolUse("a"), toolUse("b"))
        val completed = receipt("a")
        val repaired = SubagentExecutionPolicy.historyForResume(listOf(requested, completed), "Resume")

        assertEquals(3, repaired.size)
        assertEquals(listOf("a", "b"), results(repaired[1]).map { it.id })
        assertSame(results(completed).single(), results(repaired[1])[0])
        assertFalse(results(repaired[1])[0].isError)
        assertTrue(results(repaired[1])[1].isError)
        assertEquals(listOf("a", "b"), repaired.flatMap(::results).map { it.id })
        assertEquals("【用户人工干预指令】: Resume", repaired.last().content)
    }

    @Test
    fun `completed and failed receipts are not duplicated on resume`() {
        val history = listOf(assistant(toolUse("a"), toolUse("b")), receipt("a"), receipt("b", isError = true))
        val repaired = SubagentExecutionPolicy.historyForResume(history, "Try another approach")

        assertEquals(3, repaired.size)
        assertSame(history[0], repaired[0])
        assertFalse(results(repaired[1])[0].isError)
        assertTrue(results(repaired[1])[1].isError)
        assertEquals(listOf("a", "b"), repaired.flatMap(::results).map { it.id })
    }

    @Test
    fun `missing receipt is inserted before an earlier ordinary user intervention`() {
        val requested = assistant(toolUse("a"), toolUse("b"))
        val priorIntervention = LLMMessage(role = LLMMessage.Role.USER, content = "Stop editing")
        val repaired = SubagentExecutionPolicy.historyForResume(
            listOf(requested, receipt("a"), priorIntervention), "Only explain the current state",
        )

        assertEquals(listOf("a", "b"), results(repaired[1]).map { it.id })
        assertTrue(results(repaired[1])[1].isError)
        assertSame(priorIntervention, repaired[2])
        assertEquals("【用户人工干预指令】: Only explain the current state", repaired[3].content)
    }

    @Test
    fun `plain text history is preserved without fabricated tool receipts`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "Finished reading"))
        val repaired = SubagentExecutionPolicy.historyForResume(history, "Explain more")

        assertEquals(history, repaired.dropLast(1))
        assertTrue(repaired.flatMap(::results).isEmpty())
    }
}
