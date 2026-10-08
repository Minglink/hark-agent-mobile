package com.openminis.app.agent.subagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentExecutionLifecycleTest {
    private val handles = mutableListOf<SubagentHandle>()

    private fun registeredHandle() = SubagentHandle(
        parentSessionId = "lifecycle-test-${java.util.UUID.randomUUID()}",
        goal = "Read a file and report the result",
        route = AllowedModelRoute(providerId = "test", modelId = "test-model"),
        role = SubagentRole.Delegate,
    ).also {
        handles += it
        SubagentRegistry.register(it)
    }

    private fun success(handle: SubagentHandle) = SubagentResult(
        handle = handle,
        state = SubagentState.SUCCEEDED,
        summary = "Completed",
    )

    @After
    fun cleanup() {
        handles.forEach { SubagentRegistry.cancel(it.id, "test cleanup") }
        SubagentRegistry.cleanExpired(-1L)
    }

    @Test
    fun `cancellation is terminal even when a provider reports late success`() {
        val handle = registeredHandle()
        assertTrue(SubagentRegistry.cancel(handle.id, "Stop now"))

        SubagentRegistry.updateState(handle.id, SubagentState.SUCCEEDED, success(handle))
        SubagentRegistry.updateState(handle.id, SubagentState.RUNNING)
        SubagentRegistry.updateProgress(handle.id, "Thinking again")

        val record = SubagentRegistry.getRecord(handle.id)!!
        assertEquals(SubagentState.CANCELLED, record.state)
        assertEquals(SubagentState.CANCELLED, record.result!!.state)
        assertEquals("Stop now", record.result.errorMessage)
        assertEquals("已取消", record.currentStep)
        assertFalse(SubagentRegistry.cancel(handle.id))
    }

    @Test
    fun `cancel before binding prevents the execution body from starting`() = runTest {
        val handle = registeredHandle()
        SubagentRegistry.cancel(handle.id, "Cancelled while queued")
        var executed = false

        val result = SubagentExecutionLifecycle.run(handle, StandardTestDispatcher(testScheduler)) {
            executed = true
            success(handle)
        }

        assertFalse(executed)
        assertEquals(SubagentState.CANCELLED, result.state)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `stopping one subagent cancels its job and leaves parent and sibling active`() = runTest {
        val handle = registeredHandle()
        val siblingHandle = registeredHandle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var stopped = false
        val siblingRelease = CompletableDeferred<Unit>()
        val execution = async {
            SubagentExecutionLifecycle.run(handle, dispatcher) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        val sibling = async {
            SubagentExecutionLifecycle.run(siblingHandle, dispatcher) {
                siblingRelease.await()
                success(siblingHandle)
            }
        }
        runCurrent()
        assertNotNull(SubagentRegistry.getRecord(handle.id)!!.job)

        assertTrue(SubagentRegistry.cancel(handle.id, "Only this child"))
        runCurrent()

        assertEquals(SubagentState.CANCELLED, execution.await().state)
        assertTrue(stopped)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(sibling.isActive)
        siblingRelease.complete(Unit)
        assertEquals(SubagentState.SUCCEEDED, sibling.await().state)
    }

    @Test
    fun `parent cancellation propagates instead of returning a normal tool result`() = runTest {
        val handle = registeredHandle()
        var returnedNormally = false
        var stopped = false
        val parent = launch {
            SubagentExecutionLifecycle.run(handle, StandardTestDispatcher(testScheduler)) {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            returnedNormally = true
        }
        runCurrent()

        parent.cancel()
        parent.join()

        assertTrue(stopped)
        assertFalse(returnedNormally)
        assertTrue(parent.isCancelled)
        assertEquals(SubagentState.CANCELLED, SubagentRegistry.getRecord(handle.id)!!.state)
    }

    @Test
    fun `resume waits for cancelled execution cleanup and then permits a new run`() = runTest {
        val handle = registeredHandle()
        val cleanupRelease = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val execution = async {
            SubagentExecutionLifecycle.run(handle, dispatcher) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanupRelease.await() }
                }
            }
        }
        runCurrent()
        SubagentRegistry.cancel(handle.id)
        runCurrent()

        assertFalse(SubagentRegistry.prepareResume(handle))
        cleanupRelease.complete(Unit)
        assertEquals(SubagentState.CANCELLED, execution.await().state)
        assertTrue(SubagentRegistry.prepareResume(handle))

        val resumed = SubagentExecutionLifecycle.run(handle, dispatcher) { success(handle) }
        assertEquals(SubagentState.SUCCEEDED, resumed.state)
        assertEquals(SubagentState.SUCCEEDED, SubagentRegistry.getRecord(handle.id)!!.state)
    }

    @Test
    fun `turn budget exhaustion produces failure instead of success`() = runTest {
        val handle = registeredHandle()
        val result = SubagentExecutionLifecycle.run(handle, StandardTestDispatcher(testScheduler)) {
            SubagentExecutionPolicy.requireFollowUpBudget(turnsUsed = 15, maxTurns = 15)
            success(handle)
        }

        assertEquals(SubagentState.FAILED, result.state)
        assertTrue(result.errorMessage!!.contains("15"))
        assertTrue(result.errorMessage.contains("尚未完成"))
        assertEquals(SubagentState.FAILED, SubagentRegistry.getRecord(handle.id)!!.state)
    }

    @Test
    fun `tool follow up is permitted before the final turn`() {
        SubagentExecutionPolicy.requireFollowUpBudget(turnsUsed = 14, maxTurns = 15)
    }

    @Test
    fun `cancelling already completed work does not rewrite its outcome`() = runTest {
        val handle = registeredHandle()
        val result = SubagentExecutionLifecycle.run(handle, StandardTestDispatcher(testScheduler)) { success(handle) }

        assertFalse(SubagentRegistry.cancel(handle.id))
        assertEquals(result, SubagentRegistry.getRecord(handle.id)!!.result)
        assertEquals(null, SubagentRegistry.getRecord(handle.id)!!.job)
    }

    @Test
    fun `completed child cannot resume before lifecycle publishes and releases ownership`() = runTest {
        val handle = registeredHandle()
        var resumedInsideCompletion = true
        var sawOwnedJob = false

        val result = SubagentExecutionLifecycle.run(handle, StandardTestDispatcher(testScheduler)) {
            currentCoroutineContext().job.invokeOnCompletion {
                sawOwnedJob = SubagentRegistry.getRecord(handle.id)!!.job != null
                resumedInsideCompletion = SubagentRegistry.prepareResume(handle)
            }
            success(handle).also { SubagentRegistry.updateState(handle.id, it.state, it) }
        }

        assertTrue(sawOwnedJob)
        assertFalse(resumedInsideCompletion)
        assertEquals(SubagentState.SUCCEEDED, result.state)
        assertEquals(SubagentState.SUCCEEDED, SubagentRegistry.getRecord(handle.id)!!.state)
        assertEquals(null, SubagentRegistry.getRecord(handle.id)!!.job)
        assertTrue(SubagentRegistry.prepareResume(handle))
    }
}
