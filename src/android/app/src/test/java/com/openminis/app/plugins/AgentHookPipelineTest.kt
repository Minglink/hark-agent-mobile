package com.openminis.app.plugins

import com.openminis.app.plugins.hooks.AgentHook
import com.openminis.app.plugins.hooks.AgentHookPipeline
import com.openminis.app.plugins.hooks.HookContext
import com.openminis.app.plugins.hooks.ToolInterceptDecision
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentHookPipelineTest {

    private val testContext = HookContext(sessionId = "test-session-1")

    @Before
    fun setUp() {
        AgentHookPipeline.clearHooks()
    }

    @Test
    fun testSecurityGuardBlocksDestructiveCommands() {
        val dangerousCommands = listOf(
            """{"command": "rm -rf /"}""",
            """{"command": "rm -rf /*"}""",
            """{"command": "mkfs.ext4 /dev/block/bootdevice"}""",
        )

        for (cmd in dangerousCommands) {
            val decision = AgentHookPipeline.processBeforeToolExecute("shell_execute", cmd, testContext)
            assertTrue("Expected command to be blocked: $cmd", decision is ToolInterceptDecision.Block)
        }
    }

    @Test
    fun testSecurityGuardAllowsSafeCommands() {
        val safeCommands = listOf(
            """{"command": "ls -la /var/hark/workspace"}""",
            """{"command": "python3 main.py"}""",
            """{"command": "git status"}""",
        )

        for (cmd in safeCommands) {
            val decision = AgentHookPipeline.processBeforeToolExecute("shell_execute", cmd, testContext)
            assertTrue("Expected command to be allowed: $cmd", decision is ToolInterceptDecision.Allow)
        }
    }

    @Test
    fun testCustomHookModifiesArgsAndResults() {
        val customHook = object : AgentHook {
            override val id: String = "test.custom_hook"
            override val priority: Int = 500

            override fun onPromptInput(input: String, context: HookContext): String {
                return "$input [TAGGED]"
            }

            override fun onBeforeToolExecute(toolName: String, argsJson: String, context: HookContext): ToolInterceptDecision {
                if (toolName == "custom_tool") {
                    val j = JSONObject(argsJson)
                    j.put("injected", true)
                    return ToolInterceptDecision.Modify(j.toString())
                }
                return ToolInterceptDecision.Allow
            }

            override fun onAfterToolExecute(toolName: String, result: ToolExecutionResult, context: HookContext): ToolExecutionResult {
                return result.copy(output = result.output + " (intercepted)")
            }
        }

        AgentHookPipeline.registerHook(customHook)

        // Test input processing
        val transformedInput = AgentHookPipeline.processPromptInput("Hello agent", testContext)
        assertEquals("Hello agent [TAGGED]", transformedInput)

        // Test tool arg modification
        val modifiedDecision = AgentHookPipeline.processBeforeToolExecute("custom_tool", """{"orig": 123}""", testContext)
        assertTrue(modifiedDecision is ToolInterceptDecision.Modify)
        val newJson = JSONObject((modifiedDecision as ToolInterceptDecision.Modify).newArgsJson)
        assertTrue(newJson.getBoolean("injected"))

        // Test tool result interception
        val rawRes = ToolExecutionResult(output = "Success", success = true)
        val finalRes = AgentHookPipeline.processAfterToolExecute("custom_tool", rawRes, testContext)
        assertEquals("Success (intercepted)", finalRes.output)
    }

    @Test
    fun testSessionScopedHookIsolation() {
        val sessionAHook = object : AgentHook {
            override val id: String = "session.a_only"
            override val priority: Int = 300
            override fun onPromptInput(input: String, context: HookContext): String {
                return "$input [SESSION_A]"
            }
        }

        AgentHookPipeline.registerSessionHook("session-A", sessionAHook)

        val contextA = HookContext(sessionId = "session-A")
        val contextB = HookContext(sessionId = "session-B")

        // Session A should trigger the session hook
        val resA = AgentHookPipeline.processPromptInput("test query", contextA)
        assertEquals("test query [SESSION_A]", resA)

        // Session B should NOT trigger the session hook
        val resB = AgentHookPipeline.processPromptInput("test query", contextB)
        assertEquals("test query", resB)

        // Clearing session A hooks isolates cleanup
        AgentHookPipeline.clearSessionHooks("session-A")
        val resACleared = AgentHookPipeline.processPromptInput("test query", contextA)
        assertEquals("test query", resACleared)
    }
}
