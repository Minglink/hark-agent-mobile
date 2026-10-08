package com.openminis.app.agent.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** One independently cancellable execution, still owned by its caller. */
internal object SubagentExecutionLifecycle {
    suspend fun run(
        handle: SubagentHandle,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        execute: suspend () -> SubagentResult,
    ): SubagentResult = supervisorScope {
        // Bind before starting so Stop also works during provider/session init.
        // A dedicated child job prevents Stop from cancelling the main agent or
        // the other advisors. Parent cancellation still reaches this job.
        val execution = async(dispatcher, start = CoroutineStart.LAZY) { execute() }
        SubagentRegistry.bindJob(handle.id, execution)
        try {
            val result = execution.await()
            SubagentRegistry.updateState(handle.id, result.state, result)?.result ?: result
        } catch (e: CancellationException) {
            val result = SubagentResult(
                handle = handle,
                state = SubagentState.CANCELLED,
                summary = "[子代理已被取消]",
                startedAt = handle.createdAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            val recorded = SubagentRegistry.updateState(handle.id, result.state, result)?.result ?: result
            // Independent child cancellation is a tool outcome. Cancellation
            // of the caller must keep propagating through the main agent loop.
            currentCoroutineContext().ensureActive()
            recorded
        } catch (e: Exception) {
            val result = SubagentResult(
                handle = handle,
                state = SubagentState.FAILED,
                summary = "[子代理执行出错: ${e.message}]",
                startedAt = handle.createdAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message ?: "Unknown error",
            )
            SubagentRegistry.updateState(handle.id, result.state, result)?.result ?: result
        } finally {
            // A completed child may still have an await continuation queued in
            // this wrapper. Keep ownership until its outcome has been published.
            // Cancellation can also wake await before non-cancellable cleanup
            // finishes, so wait for that cleanup before making Resume available.
            withContext(NonCancellable) { execution.join() }
            SubagentRegistry.unbindJob(handle.id, execution)
        }
    }
}
