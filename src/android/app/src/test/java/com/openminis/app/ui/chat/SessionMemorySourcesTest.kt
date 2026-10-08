package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionMemorySourcesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `project sheet shows project knowledge and preferences without daily logs`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        repo.saveFile("SOUL.md", "Assistant personality")
        repo.saveGlobalMd("User preference")
        repo.writeMemory("Unrelated daily task")
        val knowledge = "Project architecture\n" + "项目知识".repeat(2100)
        repo.writeProjectMemory(project, knowledge)

        val items = buildAutoInjectedItems(repo, project)
        assertEquals(setOf("SOUL.md", "GLOBAL.md", "PROJECT.md"), items.map { it.fileName }.toSet())
        assertTrue(items.single { it.fileName == "GLOBAL.md" }.content.contains("User preference"))
        val projectItem = items.single { it.fileName == "PROJECT.md" }
        assertTrue(projectItem.content.contains(knowledge))
        assertTrue(projectItem.detail.startsWith("8000/"))
        assertFalse(projectItem.editable)
        assertFalse(items.any { it.content.contains("Unrelated daily task") })
    }

    @Test fun `empty project remains isolated and never reads PROJECT from global storage`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        repo.writeMemory("Old session task")
        repo.saveFile("PROJECT.md", "Wrong global project file")

        val items = buildAutoInjectedItems(repo, project, emptyLabel = "空白")
        val projectItem = items.single { it.fileName == "PROJECT.md" }
        assertEquals("", projectItem.content)
        assertEquals("空白", projectItem.detail)
        assertFalse(projectItem.editable)
        assertFalse(items.any { it.content.contains("Old session task") || it.content.contains("Wrong global project file") })
        assertFalse(File(project, ".hark/memory/PROJECT.md").exists())
    }

    @Test fun `non project sheet keeps legacy daily source and editing`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        repo.writeMemory("Daily note")
        val items = buildAutoInjectedItems(repo, todayLabel = "今天")
        val daily = items.single { it.content.contains("Daily note") }
        assertTrue(daily.name.startsWith("今天"))
        assertTrue(daily.editable)
        assertFalse(items.any { it.fileName == "PROJECT.md" })
    }
}
