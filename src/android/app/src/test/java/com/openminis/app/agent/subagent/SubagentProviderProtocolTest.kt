package com.openminis.app.agent.subagent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercise the actual provider conversion, including Anthropic's pre-merge orphan filtering. */
class SubagentProviderProtocolTest {
    private lateinit var server: MockWebServer
    private val task = LLMMessage(LLMMessage.Role.USER, "TASK")
    private val intervention = "USER_INTERVENTION"

    @Before fun startServer() { server = MockWebServer(); server.start() }
    @After fun stopServer() { server.shutdown() }

    private fun call(id: String, name: String) = AgentContentPart.ToolUse(
        id, name, JSONObject().put("path", "/workspace/$id"), thoughtSignature = "signature_$id",
    )

    private fun assistant(vararg calls: AgentContentPart.ToolUse) = LLMMessage(
        LLMMessage.Role.ASSISTANT, "", contentParts = calls.toList(),
    )

    private fun receipt(id: String, name: String, output: String, success: Boolean = true) =
        SubagentExecutionPolicy.toolResultMessage(id, name, ToolExecutionResult(output, success))

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }

    private suspend fun request(provider: LLMProvider, history: List<LLMMessage>, response: String): JSONObject {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
        provider.sendMessage(history, null, 1024)
        val sent = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("Provider should send a request to the local mock server", sent)
        return JSONObject(sent!!.body.readUtf8())
    }

    private suspend fun anthropicRequest(history: List<LLMMessage>): JSONObject = request(
        AnthropicProvider("test-key", LLMModel.claudeHaiku45, server.url("/").toString().trimEnd('/')),
        history,
        """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
    )

    private fun anthropicBatch(body: JSONObject, ids: Set<String>): List<JSONObject> {
        val messages = objects(body.getJSONArray("messages"))
        val assistantIndex = messages.indexOfFirst { message ->
            message.getString("role") == "assistant" &&
                objects(message.getJSONArray("content")).filter { it.optString("type") == "tool_use" }
                    .map { it.getString("id") }.toSet() == ids
        }
        assertTrue("Assistant tool declarations should be preserved for $ids", assistantIndex >= 0)
        assertTrue("Tool declarations must have an immediately following response", assistantIndex + 1 < messages.size)
        val next = messages[assistantIndex + 1]
        assertEquals("user", next.getString("role"))
        val blocks = objects(next.getJSONArray("content"))
        val results = blocks.filter { it.optString("type") == "tool_result" }
        assertEquals("All responses must survive Anthropic's orphan filter in the first USER message", ids, results.map { it.getString("tool_use_id") }.toSet())
        assertEquals("Each tool must have exactly one response", ids.size, results.size)
        val firstText = blocks.indexOfFirst { it.optString("type") == "text" }
        if (firstText >= 0) {
            assertTrue("All tool_result blocks must precede user intervention text", firstText > blocks.indexOfLast { it.optString("type") == "tool_result" })
        }
        return blocks
    }

    @Test
    fun `Anthropic preserves a completed response and the missing response before intervention`() = runBlocking {
        val history = mutableListOf(task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")))
        SubagentExecutionPolicy.appendToolResultToHistory(history, "call_a", "file_read", ToolExecutionResult("READ_A_CONFIRMED", true))
        val body = anthropicRequest(SubagentExecutionPolicy.historyForResume(history, intervention))
        val blocks = anthropicBatch(body, setOf("call_a", "call_b"))
        val results = blocks.filter { it.optString("type") == "tool_result" }.associateBy { it.getString("tool_use_id") }

        assertEquals("READ_A_CONFIRMED", results.getValue("call_a").getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(results.getValue("call_a").optBoolean("is_error"))
        assertTrue(results.getValue("call_b").getBoolean("is_error"))
        assertTrue(blocks.last().getString("text").contains(intervention))
    }

    @Test
    fun `Anthropic receives the whole normal tool batch without requiring a resume repair`() = runBlocking {
        val history = mutableListOf(task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")))
        SubagentExecutionPolicy.appendToolResultToHistory(history, "call_a", "file_read", ToolExecutionResult("READ_A_CONFIRMED", true))
        SubagentExecutionPolicy.appendToolResultToHistory(history, "call_b", "file_edit", ToolExecutionResult("EDIT_B_FAILED", false))

        val blocks = anthropicBatch(anthropicRequest(history), setOf("call_a", "call_b"))
        assertEquals(2, blocks.size)
        assertEquals("READ_A_CONFIRMED", blocks[0].getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("EDIT_B_FAILED", blocks[1].getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(blocks[0].optBoolean("is_error"))
        assertTrue(blocks[1].getBoolean("is_error"))
    }

    @Test
    fun `Anthropic retains both already recorded split responses and their error flags`() = runBlocking {
        val history = listOf(
            task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")),
            receipt("call_a", "file_read", "A_OUTPUT"), receipt("call_b", "file_edit", "B_FAILED_OUTPUT", success = false),
        )
        val blocks = anthropicBatch(anthropicRequest(SubagentExecutionPolicy.historyForResume(history, intervention)), setOf("call_a", "call_b"))
        val results = blocks.filter { it.optString("type") == "tool_result" }.associateBy { it.getString("tool_use_id") }
        assertEquals("B_FAILED_OUTPUT", results.getValue("call_b").getJSONArray("content").getJSONObject(0).getString("text"))
        assertTrue(results.getValue("call_b").getBoolean("is_error"))
        assertFalse(results.getValue("call_a").optBoolean("is_error"))
        assertTrue(blocks.last().getString("text").contains(intervention))
    }

    @Test
    fun `Anthropic receives all cancelled tool responses when no tool had finished`() = runBlocking {
        val history = listOf(task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")))
        val blocks = anthropicBatch(anthropicRequest(SubagentExecutionPolicy.historyForResume(history, intervention)), setOf("call_a", "call_b"))
        assertTrue(blocks.filter { it.optString("type") == "tool_result" }.all { it.getBoolean("is_error") })
        assertTrue(blocks.last().getString("text").contains(intervention))
    }

    @Test
    fun `Anthropic inserts missing responses before existing and new user corrections`() = runBlocking {
        val history = listOf(
            task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")),
            receipt("call_a", "file_read", "A_OUTPUT"), LLMMessage(LLMMessage.Role.USER, "EARLIER_CORRECTION"),
        )
        val blocks = anthropicBatch(anthropicRequest(SubagentExecutionPolicy.historyForResume(history, intervention)), setOf("call_a", "call_b"))
        val text = blocks.filter { it.optString("type") == "text" }.map { it.getString("text") }
        assertTrue(text.first().contains("EARLIER_CORRECTION"))
        assertTrue(text.last().contains(intervention))
    }

    @Test
    fun `Anthropic repairs each historical tool batch without crossing assistant boundaries`() = runBlocking {
        val history = listOf(
            task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")),
            receipt("call_a", "file_read", "A_OUTPUT"),
            assistant(call("call_c", "file_write"), call("call_d", "file_read")),
            receipt("call_d", "file_read", "D_OUTPUT"),
        )
        val body = anthropicRequest(SubagentExecutionPolicy.historyForResume(history, intervention))
        anthropicBatch(body, setOf("call_a", "call_b"))
        val lastBatch = anthropicBatch(body, setOf("call_c", "call_d"))
        assertTrue(lastBatch.last().getString("text").contains(intervention))
    }

    @Test
    fun `OpenAI keeps grouped responses contiguous with their declarations before new user text`() = runBlocking {
        val history = SubagentExecutionPolicy.historyForResume(
            listOf(task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")), receipt("call_a", "file_read", "A_OUTPUT")), intervention,
        )
        val body = request(
            OpenAIProvider("test-key", LLMModel.gpt4oMini, server.url("/").toString().trimEnd('/')),
            history,
            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
        )
        val messages = objects(body.getJSONArray("messages"))
        val assistantIndex = messages.indexOfFirst { it.optJSONArray("tool_calls") != null }
        assertTrue("Assistant tool declarations must remain in OpenAI history", assistantIndex >= 0)
        val responses = messages.subList(assistantIndex + 1, assistantIndex + 3)
        assertEquals(listOf("tool", "tool"), responses.map { it.getString("role") })
        assertEquals(listOf("call_a", "call_b"), responses.map { it.getString("tool_call_id") })
        assertEquals("A_OUTPUT", responses[0].getString("content"))
        assertTrue(responses[1].getString("content").contains("interrupted"))
        val nextUser = messages[assistantIndex + 3]
        assertEquals("user", nextUser.getString("role"))
        assertTrue(nextUser.getString("content").contains(intervention))
    }

    @Test
    fun `Gemini keeps paired function responses and signatures before the intervention`() = runBlocking {
        val history = SubagentExecutionPolicy.historyForResume(
            listOf(task, assistant(call("call_a", "file_read"), call("call_b", "file_edit")), receipt("call_a", "file_read", "A_OUTPUT")), intervention,
        )
        for (model in listOf(LLMModel.gemini25Flash, LLMModel.gemini3Flash)) {
            val body = request(
                GeminiProvider("test-key", model, server.url("/").toString().trimEnd('/')),
                history,
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}""",
            )
            val contents = objects(body.getJSONArray("contents"))
            val assistantIndex = contents.indexOfFirst { it.getString("role") == "model" }
            assertTrue("Assistant tool declarations must remain in Gemini history", assistantIndex >= 0)
            val calls = objects(contents[assistantIndex].getJSONArray("parts"))
            assertEquals(listOf("file_read", "file_edit"), calls.map { it.getJSONObject("functionCall").getString("name") })
            if (model == LLMModel.gemini3Flash) {
                assertEquals(listOf("signature_call_a", "signature_call_b"), calls.map { it.getString("thoughtSignature") })
            }
            val resultMessage = contents[assistantIndex + 1]
            assertEquals("user", resultMessage.getString("role"))
            val responses = objects(resultMessage.getJSONArray("parts")).map { it.getJSONObject("functionResponse") }
            assertEquals(listOf("file_read", "file_edit"), responses.map { it.getString("name") })
            assertEquals("A_OUTPUT", responses[0].getJSONObject("response").getString("result"))
            assertFalse(responses[0].getJSONObject("response").optBoolean("error"))
            assertTrue(responses[1].getJSONObject("response").getBoolean("error"))
            val following = contents[assistantIndex + 2]
            assertEquals("user", following.getString("role"))
            assertTrue(following.getJSONArray("parts").getJSONObject(0).getString("text").contains(intervention))
        }
    }
}
