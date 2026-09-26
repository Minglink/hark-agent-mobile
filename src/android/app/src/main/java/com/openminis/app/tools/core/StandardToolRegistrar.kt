package com.openminis.app.tools.core

import com.openminis.app.sandbox.ShellExecutor
import com.openminis.app.tools.DelegateTaskTool
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.TodoWriteTool
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * Automatically registers all standard Hark agent tool handlers into ToolExecutionDispatcher.
 */
object StandardToolRegistrar {

    private var initialized = false

    @Synchronized
    fun ensureRegistered() {
        if (initialized) return

        // 1. DelegateTaskTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = DelegateTaskTool.TOOL_DELEGATE
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                val json = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
                val providerRepo = com.openminis.app.data.repository.ProviderRepository(context.context)
                val subagentRepo = com.openminis.app.data.repository.SubagentRepository(context.context, providerRepo)
                return DelegateTaskTool.executeDelegate(
                    context = context.context,
                    providerRepository = providerRepo,
                    subagentRepository = subagentRepo,
                    parentSessionId = context.sessionId,
                    currentEntry = null,
                    args = json,
                )
            }
        })

        // 2. FileReadTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = FileReadTool.NAME
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                return FileReadTool.execute(argsJson, context.sessionId, context.context)
            }
        })

        // 3. FileWriteTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = FileWriteTool.NAME
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                return FileWriteTool.execute(argsJson, context.sessionId, context.context)
            }
        })

        // 4. FileEditTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = FileEditTool.NAME
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                return FileEditTool.execute(argsJson, context.sessionId, context.context)
            }
        })

        // 5. TodoWriteTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = TodoWriteTool.NAME
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                return TodoWriteTool.execute(argsJson) { /* onUpdate handled by UI/ViewModel */ }
            }
        })

        // 6. ReadImageTool
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = ReadImageTool.NAME
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                return ReadImageTool.execute(argsJson, context.sessionId, context.context)
            }
        })

        // 7. shell_execute
        ToolExecutionDispatcher.register(object : AgentToolHandler {
            override val name: String get() = "shell_execute"
            override suspend fun execute(argsJson: String, context: ToolExecutionContext): ToolExecutionResult {
                val json = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
                val command = json.optString("command", "")
                val title = json.optString("tool_title", "Execute Shell")
                val timeoutSec = json.optLong("timeout", 600L)
                val delaySec = json.optLong("delay", 0L)

                if (delaySec > 0) {
                    kotlinx.coroutines.delay(delaySec * 1000L)
                }

                val res = ShellExecutor.execute(
                    context = context.context,
                    command = command,
                    timeout = timeoutSec * 1000L
                )

                return ToolExecutionResult(
                    toolTitle = title,
                    output = res.output,
                    success = res.exitCode == 0
                )
            }
        })

        initialized = true
    }
}
