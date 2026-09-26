package com.openminis.app.tools.core

import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatches and orchestrates tool execution.
 * Provides error boundaries, timeout protections, and performance telemetry.
 */
object ToolExecutionDispatcher {

    private const val TAG = "ToolExecutionDispatcher"
    private const val DEFAULT_TIMEOUT_MS = 600_000L // 10 minutes

    private val handlers = ConcurrentHashMap<String, AgentToolHandler>()

    fun register(handler: AgentToolHandler) {
        handlers[handler.name] = handler
    }

    fun unregister(name: String) {
        handlers.remove(name)
    }

    fun getHandler(name: String): AgentToolHandler? = handlers[name]

    fun hasHandler(name: String): Boolean = handlers.containsKey(name)

    /**
     * Dispatches tool execution with timeout protection and structured error boundaries.
     */
    suspend fun dispatch(
        name: String,
        argsJson: String,
        context: ToolExecutionContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ToolExecutionResult {
        val handler = handlers[name]
            ?: return ToolExecutionResult(
                output = "Tool '$name' is not registered in the system dispatcher.",
                success = false,
                toolTitle = "Unknown Tool"
            )

        val start = System.currentTimeMillis()
        AppLogger.info(TAG, "Dispatching tool: $name for session: ${context.sessionId}")

        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                handler.execute(argsJson, context)
            }

            if (result == null) {
                AppLogger.warning(TAG, "Tool $name timed out after ${timeoutMs}ms")
                ToolExecutionResult(
                    output = "Tool execution exceeded maximum allowed time limit (${timeoutMs / 1000}s).",
                    success = false,
                    toolTitle = "Execution Timed Out",
                    timedOut = true
                )
            } else {
                val elapsed = System.currentTimeMillis() - start
                AppLogger.info(TAG, "Tool $name completed in ${elapsed}ms, success=${result.success}")
                result
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Unexpected crash during tool $name execution: ${t.localizedMessage ?: t.javaClass.simpleName}")
            ToolExecutionResult(
                output = "Unexpected exception during tool execution: ${t.localizedMessage ?: t.javaClass.simpleName}",
                success = false,
                toolTitle = "Execution Error"
            )
        }
    }
}
