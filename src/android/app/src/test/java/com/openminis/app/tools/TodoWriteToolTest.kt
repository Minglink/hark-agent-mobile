package com.openminis.app.tools

import com.openminis.app.data.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoWriteToolTest {

    @Test
    fun parseTodos_validJsonArrayString() {
        val json = """
            {
                "tool_title": "Plan work",
                "todos": [
                    {"id": "1", "content": "Explore files", "status": "completed", "priority": "high"},
                    {"id": "2", "content": "Refactor logic", "status": "in_progress", "priority": "medium"},
                    {"id": "3", "content": "Write unit tests", "status": "pending", "priority": "low"}
                ]
            }
        """.trimIndent()

        val todos = TodoWriteTool.parseTodos(json)
        assertEquals(3, todos.size)

        assertEquals("1", todos[0].id)
        assertEquals("Explore files", todos[0].content)
        assertTrue(todos[0].isCompleted)
        assertEquals("high", todos[0].priority)

        assertEquals("2", todos[1].id)
        assertEquals("Refactor logic", todos[1].content)
        assertTrue(todos[1].isInProgress)
        assertEquals("medium", todos[1].priority)

        assertEquals("3", todos[2].id)
        assertEquals("Write unit tests", todos[2].content)
        assertTrue(todos[2].isPending)
        assertEquals("low", todos[2].priority)
    }

    @Test
    fun execute_updatesTodosAndReturnsFormattedSummary() {
        val json = """
            {
                "tool_title": "Task plan",
                "todos": [
                    {"content": "Step 1", "status": "completed"},
                    {"content": "Step 2", "status": "pending"}
                ]
            }
        """.trimIndent()

        var updatedList: List<TodoItem>? = null
        val result = TodoWriteTool.execute(json) { list ->
            updatedList = list
        }

        assertTrue(result.success)
        assertEquals(2, updatedList?.size)
        assertTrue(result.output.contains("[x] 1. Step 1"))
        assertTrue(result.output.contains("[ ] 2. Step 2"))
    }

    @Test
    fun execute_emptyListFailsGracefully() {
        val json = """{"todos": []}"""
        var called = false
        val result = TodoWriteTool.execute(json) { called = true }
        assertFalse(result.success)
        assertFalse(called)
    }
}
