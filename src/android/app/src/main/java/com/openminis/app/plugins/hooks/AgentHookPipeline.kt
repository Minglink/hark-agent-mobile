package com.openminis.app.plugins.hooks

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.plugins.harkpkg.HarkPkgManager
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class HookContext(
    val sessionId: String,
    val context: Context? = null,
    val activeModel: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

sealed class ToolInterceptDecision {
    object Allow : ToolInterceptDecision()
    data class Modify(val newArgsJson: String) : ToolInterceptDecision()
    data class Block(val reason: String) : ToolInterceptDecision()
}

interface AgentHook {
    val id: String
    val priority: Int get() = 100 // Higher priority runs earlier

    fun onPromptInput(input: String, context: HookContext): String = input

    fun onSystemPromptCompose(basePrompt: String, context: HookContext): String = basePrompt

    fun onBeforeToolExecute(
        toolName: String,
        argsJson: String,
        context: HookContext,
    ): ToolInterceptDecision = ToolInterceptDecision.Allow

    fun onAfterToolExecute(
        toolName: String,
        result: ToolExecutionResult,
        context: HookContext,
    ): ToolExecutionResult = result
}

/**
 * Built-in Security Guard Hook: Intercepts dangerous operations before tool execution.
 */
class DefaultSecurityGuardHook : AgentHook {
    override val id: String = "core.security_guard"
    override val priority: Int = 1000 // Very high priority

    private val blockedShellPatterns = listOf(
        Regex("""rm\s+.*-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+.*(/($|\s|;|\*)|--no-preserve-root)"""),
        Regex("""mkfs(\.\w+)?\s+"""),
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""), // fork bomb
        Regex("""dd\s+if=/dev/zero\s+of=/dev/"""),
    )

    override fun onBeforeToolExecute(
        toolName: String,
        argsJson: String,
        context: HookContext,
    ): ToolInterceptDecision {
        if (toolName == "shell_execute") {
            try {
                val j = JSONObject(argsJson)
                val cmd = j.optString("command", "").trim()
                for (pattern in blockedShellPatterns) {
                    if (pattern.containsMatchIn(cmd)) {
                        return ToolInterceptDecision.Block(
                            "Security Violation: Command contains potentially destructive operation: '$cmd'. Execution blocked."
                        )
                    }
                }
            } catch (_: Throwable) {}
        }
        return ToolInterceptDecision.Allow
    }
}

/**
 * Built-in HarkPkg Hook: Injects enabled .harkpkg system prompt addons into system prompt composition.
 */
class HarkPkgPromptHook : AgentHook {
    override val id: String = "core.harkpkg_prompt_injector"
    override val priority: Int = 200

    override fun onSystemPromptCompose(basePrompt: String, context: HookContext): String {
        val addons = HarkPkgManager.activeSystemPromptAddons()
        if (addons.isEmpty()) return basePrompt

        val joinedAddons = addons.joinToString("\n\n---\n\n")
        return "$basePrompt\n\n# Active Extensions & Plugin Capabilities\n$joinedAddons"
    }
}

/**
 * AgentHookPipeline coordinates all registered hooks during prompt building and tool execution.
 */
object AgentHookPipeline {
    private const val TAG = "AgentHookPipeline"
    private val globalHooks = CopyOnWriteArrayList<AgentHook>()
    private val sessionHooks = ConcurrentHashMap<String, CopyOnWriteArrayList<AgentHook>>()

    init {
        // Register standard core hooks by default
        registerHook(DefaultSecurityGuardHook())
        registerHook(HarkPkgPromptHook())
    }

    @Synchronized
    fun registerHook(hook: AgentHook) {
        val list = globalHooks.filter { it.id != hook.id }.toMutableList()
        list.add(hook)
        list.sortByDescending { it.priority }
        globalHooks.clear()
        globalHooks.addAll(list)
        AppLogger.info(TAG, "Registered global hook: ${hook.id} (priority=${hook.priority})")
    }

    @Synchronized
    fun unregisterHook(hookId: String) {
        globalHooks.removeAll { it.id == hookId }
        AppLogger.info(TAG, "Unregistered global hook: $hookId")
    }

    @Synchronized
    fun registerSessionHook(sessionId: String, hook: AgentHook) {
        val list = sessionHooks.getOrPut(sessionId) { CopyOnWriteArrayList() }
        val updated = list.filter { it.id != hook.id }.toMutableList()
        updated.add(hook)
        updated.sortByDescending { it.priority }
        list.clear()
        list.addAll(updated)
        AppLogger.info(TAG, "Registered session hook for $sessionId: ${hook.id} (priority=${hook.priority})")
    }

    @Synchronized
    fun unregisterSessionHook(sessionId: String, hookId: String) {
        sessionHooks[sessionId]?.removeAll { it.id == hookId }
        AppLogger.info(TAG, "Unregistered session hook for $sessionId: $hookId")
    }

    @Synchronized
    fun clearSessionHooks(sessionId: String) {
        sessionHooks.remove(sessionId)
        AppLogger.info(TAG, "Cleared session hooks for session: $sessionId")
    }

    @Synchronized
    fun clearHooks() {
        globalHooks.clear()
        sessionHooks.clear()
        registerHook(DefaultSecurityGuardHook())
        registerHook(HarkPkgPromptHook())
    }

    fun activeHooks(sessionId: String? = null): List<AgentHook> {
        val sHooks = if (sessionId != null) sessionHooks[sessionId]?.toList() ?: emptyList() else emptyList()
        val combined = (globalHooks.toList() + sHooks).distinctBy { it.id }
        return combined.sortedByDescending { it.priority }
    }

    fun processPromptInput(rawInput: String, context: HookContext): String {
        var current = rawInput
        for (hook in activeHooks(context.sessionId)) {
            try {
                current = hook.onPromptInput(current, context)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Error in hook ${hook.id}.onPromptInput: ${t.message}")
            }
        }
        return current
    }

    fun processSystemPromptCompose(basePrompt: String, context: HookContext): String {
        var current = basePrompt
        for (hook in activeHooks(context.sessionId)) {
            try {
                current = hook.onSystemPromptCompose(current, context)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Error in hook ${hook.id}.onSystemPromptCompose: ${t.message}")
            }
        }
        return current
    }

    fun processBeforeToolExecute(
        toolName: String,
        argsJson: String,
        context: HookContext,
    ): ToolInterceptDecision {
        var currentArgs = argsJson
        for (hook in activeHooks(context.sessionId)) {
            try {
                val decision = hook.onBeforeToolExecute(toolName, currentArgs, context)
                when (decision) {
                    is ToolInterceptDecision.Block -> {
                        AppLogger.info(TAG, "Tool $toolName blocked by hook ${hook.id}: ${decision.reason}")
                        return decision
                    }
                    is ToolInterceptDecision.Modify -> {
                        currentArgs = decision.newArgsJson
                    }
                    ToolInterceptDecision.Allow -> {}
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Error in hook ${hook.id}.onBeforeToolExecute: ${t.message}")
            }
        }
        return if (currentArgs != argsJson) ToolInterceptDecision.Modify(currentArgs) else ToolInterceptDecision.Allow
    }

    fun processAfterToolExecute(
        toolName: String,
        result: ToolExecutionResult,
        context: HookContext,
    ): ToolExecutionResult {
        var currentResult = result
        for (hook in activeHooks(context.sessionId)) {
            try {
                currentResult = hook.onAfterToolExecute(toolName, currentResult, context)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Error in hook ${hook.id}.onAfterToolExecute: ${t.message}")
            }
        }
        return currentResult
    }
}
