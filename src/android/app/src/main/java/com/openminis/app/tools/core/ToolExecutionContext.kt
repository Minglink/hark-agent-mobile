package com.openminis.app.tools.core

import android.content.Context
import com.openminis.app.tools.ToolExecutionResult

/**
 * Execution context provided to all tool handlers.
 */
data class ToolExecutionContext(
    val sessionId: String,
    val context: Context,
    val toolId: String = "",
    val assistantId: String = "",
    val currentText: String = "",
    val parameters: Map<String, Any?> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap()
)

/**
 * Standard interface for all agent tool handlers.
 */
interface AgentToolHandler {
    val name: String

    /**
     * Executes the tool invocation with arguments and context.
     */
    suspend fun execute(
        argsJson: String,
        context: ToolExecutionContext
    ): ToolExecutionResult
}
