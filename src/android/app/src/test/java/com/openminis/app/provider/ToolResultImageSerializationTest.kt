package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-toolresult-image-dropped]
 *
 * Regression lock for the reported bug: `read_image` returned its pixels on the
 * ToolResult, the tool card rendered a green success — and the model answered
 * "I can't actually see pixels", because the request builder serialized a tool
 * result as `{role:"tool", tool_call_id, content}` and never read
 * `ToolResult.imageData`. The bytes died at the provider boundary with no error
 * anywhere, which is why it read as "read_image can't see the image".
 *
 * What is asserted is the request BODY, not a mocked HTTP exchange: the defect
 * was purely in JSON construction, and MockWebServer would only add a network
 * dependency to that question.
 */
class ToolResultImageSerializationTest {

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    /** A model that declares vision the way models.dev spells it. */
    private val visionModel = LLMModel(
        id = "vision-model",
        displayName = "Vision Model",
        provider = "test",
        inputModalities = listOf("text", "image"),
    )

    /** The same capability spelled the way OpenAI / OpenRouter report it. */
    private val visionModelSuffixForm = visionModel.copy(
        id = "vision-model-suffix",
        inputModalities = listOf("text", "image_input"),
    )

    private val textOnlyModel = LLMModel(
        id = "text-model",
        displayName = "Text Model",
        provider = "test",
        inputModalities = listOf("text"),
    )

    private fun provider(model: LLMModel, useResponsesAPI: Boolean = false) =
        OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = "https://example.invalid/v1",
            useResponsesAPI = useResponsesAPI,
        )

    /** A history shaped like read_image's: assistant tool_use → tool result with pixels. */
    private fun historyWithToolResultImage(mime: String = "image/jpeg") = listOf(
        LLMMessage(LLMMessage.Role.USER, "look at the chart"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "call_abc123",
                    name = "read_image",
                    input = JSONObject("""{"path":"/var/hark/chart.png"}"""),
                ),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_abc123",
                    name = "read_image",
                    content = "[/var/hark/chart.png | 1635x1064 | 273116 bytes]",
                    imageData = pngBytes,
                    imageMimeType = mime,
                ),
            ),
        ),
    )

    private fun bodyOf(p: OpenAIProvider, messages: List<LLMMessage>): JSONObject =
        p.buildRequestBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )

    /** Collect every "type" value appearing anywhere in the payload. */
    private fun collectTypes(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                node.optString("type").takeIf { it.isNotEmpty() }?.let { out.add(it) }
                for (k in node.keys()) collectTypes(node.get(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTypes(node.get(i), out)
        }
    }

    private fun typesIn(body: JSONObject): List<String> =
        mutableListOf<String>().also { collectTypes(body, it) }

    @Test
    fun `chat completions emits an image_url block for a tool-result image`() {
        val body = bodyOf(provider(visionModel), historyWithToolResultImage())
        val types = typesIn(body)

        assertTrue(
            "tool-result pixels must reach the wire as an image_url block; types=$types",
            types.contains("image_url"),
        )
        assertTrue(
            "the base64 payload must actually be inlined",
            body.toString().contains("data:image/jpeg;base64,"),
        )
    }

    @Test
    fun `the tool message itself is still emitted and still carries its text`() {
        val body = bodyOf(provider(visionModel), historyWithToolResultImage())
        val msgs = body.getJSONArray("messages")

        var toolMsg: JSONObject? = null
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (m.optString("role") == "tool") toolMsg = m
        }
        assertTrue("a role=tool message must still be present", toolMsg != null)
        assertEquals("call_abc123", toolMsg!!.optString("tool_call_id"))
        assertTrue(
            "the tool result's text (metadata line) must survive",
            toolMsg.optString("content").contains("1635x1064"),
        )
    }

    @Test
    fun `image rides on a user turn after the tool message, not inside it`() {
        val body = bodyOf(provider(visionModel), historyWithToolResultImage())
        val msgs = body.getJSONArray("messages")

        var toolIdx = -1
        var imageIdx = -1
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (m.optString("role") == "tool") toolIdx = i
            if (typesIn(m).contains("image_url")) imageIdx = i
        }
        assertTrue("tool message found", toolIdx >= 0)
        assertTrue("image-bearing message found", imageIdx >= 0)
        // Chat Completions has no image block inside a `tool` message — its
        // content is a plain string — so the pixels must follow it.
        assertTrue(
            "image must come AFTER the tool message (idx tool=$toolIdx image=$imageIdx)",
            imageIdx > toolIdx,
        )
    }

    @Test
    fun `suffix-spelled vision model also gets the pixels`() {
        // Guards the second half of the fix: supportsImages used to be a raw
        // `"image" in inputModalities` membership test, so "image_input" read as
        // "no vision" and the pixels were swapped for a placeholder.
        val body = bodyOf(provider(visionModelSuffixForm), historyWithToolResultImage())
        assertTrue(
            "a model declaring image_input is vision-capable and must receive pixels",
            typesIn(body).contains("image_url"),
        )
    }

    @Test
    fun `text-only model gets no pixels`() {
        // The complement: a model that genuinely cannot see must not be sent
        // image blocks (the provider would 400), and the tool text still lands.
        val body = bodyOf(provider(textOnlyModel), historyWithToolResultImage())
        val types = typesIn(body)
        assertFalse(
            "text-only model must not receive an image_url block; types=$types",
            types.contains("image_url"),
        )
        assertTrue(
            "but its tool result text must still be present",
            body.toString().contains("1635x1064"),
        )
    }

    @Test
    fun `responses api emits an input_image block for a tool-result image`() {
        // The Responses path is a separate builder, not a flag on buildRequestBody.
        val body = provider(visionModel, useResponsesAPI = true).buildResponsesAPIBody(
            messages = historyWithToolResultImage(),
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
        )
        val types = typesIn(body)
        assertTrue(
            "function_call_output takes a string output, so pixels must ride a " +
                "following user turn as input_image; types=$types",
            types.contains("input_image"),
        )
        assertTrue(
            "the function_call_output itself must still be emitted",
            types.contains("function_call_output"),
        )
    }

    @Test
    fun `a tool result with no image is unchanged`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "run it"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "call_plain",
                        name = "shell_execute",
                        input = JSONObject("""{"command":"ls"}"""),
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "call_plain",
                        name = "shell_execute",
                        content = "ok",
                    ),
                ),
            ),
        )
        val body = bodyOf(provider(visionModel), history)
        assertFalse(
            "no image bytes → no image block",
            typesIn(body).contains("image_url"),
        )
    }

    @Test
    fun `multi-tool call with image tool result emits contiguous tool messages before deferred user image`() {
        // [T-toolcalls-image-400] Assistant declares two tool calls (c1 = read_image, c2 = search).
        // c1 returns image bytes, c2 returns text.
        // Serialization MUST emit:
        //   assistant(tool_calls=[c1, c2])
        //   tool(c1)
        //   tool(c2)
        //   user(image_url)  <-- AFTER all tool messages, never interleaved!
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "analyze screen and search"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "c1",
                        name = "read_image",
                        input = JSONObject("""{"path":"screen.png"}"""),
                    ),
                    AgentContentPart.ToolUse(
                        id = "c2",
                        name = "search_code",
                        input = JSONObject("""{"query":"Hero"}"""),
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "c1",
                        name = "read_image",
                        content = "[screen.png | 2400x1080 | 1493258 bytes]",
                        imageData = pngBytes,
                        imageMimeType = "image/png",
                    ),
                    AgentContentPart.ToolResult(
                        id = "c2",
                        name = "search_code",
                        content = "Hero entity found at 0x2b2b43",
                    ),
                ),
            ),
        )

        val body = bodyOf(provider(visionModel), history)
        val msgs = body.getJSONArray("messages")

        var assistantIdx = -1
        var tool1Idx = -1
        var tool2Idx = -1
        var imageUserIdx = -1

        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val role = m.optString("role")
            if (role == "assistant" && m.has("tool_calls")) assistantIdx = i
            if (role == "tool" && m.optString("tool_call_id") == "c1") tool1Idx = i
            if (role == "tool" && m.optString("tool_call_id") == "c2") tool2Idx = i
            if (role == "user" && typesIn(m).contains("image_url")) imageUserIdx = i
        }

        assertTrue("assistant message present", assistantIdx >= 0)
        assertEquals("tool c1 must immediately follow assistant", assistantIdx + 1, tool1Idx)
        assertEquals("tool c2 must immediately follow tool c1", tool1Idx + 1, tool2Idx)
        assertTrue("deferred image user message must follow all tool messages", imageUserIdx > tool2Idx)
    }

    @Test
    fun `sanitizeChatMessages repairs interleaved user message in damaged history`() {
        // Simulates historical session where an interleaved user image message was stored:
        // assistant(calls=[c1, c2]) -> tool(c1) -> user(image) -> tool(c2) -> user("继续")
        val raw = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "assistant")
                put("tool_calls", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "c1")
                        put("type", "function")
                        put("function", JSONObject().put("name", "read_image"))
                    })
                    put(JSONObject().apply {
                        put("id", "c2")
                        put("type", "function")
                        put("function", JSONObject().put("name", "search"))
                    })
                })
            })
            put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", "c1")
                put("content", "[image metadata]")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "[Interleaved image user turn]")
            })
            put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", "c2")
                put("content", "search result")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "继续")
            })
        }

        val sanitized = OpenAIProvider.sanitizeChatMessages(raw)

        assertEquals(5, sanitized.length())
        assertEquals("assistant", sanitized.getJSONObject(0).getString("role"))
        assertEquals("tool", sanitized.getJSONObject(1).getString("role"))
        assertEquals("c1", sanitized.getJSONObject(1).getString("tool_call_id"))
        assertEquals("tool", sanitized.getJSONObject(2).getString("role"))
        assertEquals("c2", sanitized.getJSONObject(2).getString("tool_call_id"))
        assertEquals("user", sanitized.getJSONObject(3).getString("role"))
        assertEquals("[Interleaved image user turn]", sanitized.getJSONObject(3).getString("content"))
        assertEquals("user", sanitized.getJSONObject(4).getString("role"))
        assertEquals("继续", sanitized.getJSONObject(4).getString("content"))
    }

    @Test
    fun `sanitizeChatMessages synthesizes error result for missing tool result`() {
        // Assistant declared [c1, c2], but only c1 had a tool response (e.g. crash/interrupted)
        val raw = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "assistant")
                put("tool_calls", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "c1")
                        put("type", "function")
                    })
                    put(JSONObject().apply {
                        put("id", "c2")
                        put("type", "function")
                    })
                })
            })
            put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", "c1")
                put("content", "ok")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "what happened?")
            })
        }

        val sanitized = OpenAIProvider.sanitizeChatMessages(raw)

        assertEquals(4, sanitized.length())
        assertEquals("c1", sanitized.getJSONObject(1).getString("tool_call_id"))
        assertEquals("c2", sanitized.getJSONObject(2).getString("tool_call_id"))
        assertTrue(
            "synthetic error receipt generated for missing c2",
            sanitized.getJSONObject(2).getString("content").contains("tool call produced no result"),
        )
        assertEquals("user", sanitized.getJSONObject(3).getString("role"))
    }

    @Test
    fun `sanitizeChatMessages drops orphan tool messages`() {
        val raw = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "hello")
            })
            put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", "orphan_call")
                put("content", "orphan content")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "continue")
            })
        }

        val sanitized = OpenAIProvider.sanitizeChatMessages(raw)
        assertEquals(2, sanitized.length())
        assertEquals("user", sanitized.getJSONObject(0).getString("role"))
        assertEquals("user", sanitized.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sanitizeChatMessages drops empty assistant messages`() {
        val raw = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "hello")
            })
            put(JSONObject().apply {
                put("role", "assistant")
                put("content", "")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "world")
            })
        }

        val sanitized = OpenAIProvider.sanitizeChatMessages(raw)
        assertEquals(2, sanitized.length())
        assertEquals("hello", sanitized.getJSONObject(0).getString("content"))
        assertEquals("world", sanitized.getJSONObject(1).getString("content"))
    }
}
