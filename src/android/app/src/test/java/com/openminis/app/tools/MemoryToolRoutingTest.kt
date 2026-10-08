package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryToolRoutingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `default write captures project destination for later edit and revoke`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        val result = MemoryTools.executeMemoryWrite("""{"content":"project convention","tool_title":"保存项目习惯"}""", repo, project)
        assertTrue(result.success)
        assertEquals("保存项目习惯", result.toolTitle)
        assertEquals("project", result.scope)
        assertEquals(project.canonicalPath, result.projectWorkspacePath)
        assertTrue(File(project, ".hark/memory/PROJECT.md").readText().contains("project convention"))
        assertFalse(repo.getMemory("", "daily").contains("project convention"))
    }

    @Test fun `non project writes and old target global calls retain daily destination`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        val outside = MemoryTools.executeMemoryWrite("""{"content":"outside note"}""", repo)
        val legacy = MemoryTools.executeMemoryWrite("""{"content":"legacy global daily","target":"global"}""", repo, project)
        val legacyAuto = MemoryTools.executeMemoryWrite("""{"content":"legacy auto daily","scope":"auto","target":"global"}""", repo, project)
        val explicit = MemoryTools.executeMemoryWrite("""{"content":"explicit daily","scope":"daily"}""", repo, project)
        listOf(outside, legacy, legacyAuto, explicit).forEach {
            assertTrue(it.success)
            assertEquals("daily", it.scope)
            assertNull(it.projectWorkspacePath)
        }
        assertFalse(File(project, ".hark/memory/PROJECT.md").exists())
        val daily = repo.getMemory("", "daily")
        assertTrue(daily.contains("outside note"))
        assertTrue(daily.contains("legacy global daily"))
        assertTrue(daily.contains("legacy auto daily"))
        assertTrue(daily.contains("explicit daily"))
    }

    @Test fun `explicit project scope overrides old target global`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        val result = MemoryTools.executeMemoryWrite("""{"content":"explicit project","scope":"project","target":"global"}""", repo, project)
        assertTrue(result.success)
        assertEquals("project", result.scope)
        assertFalse(repo.getMemory("", "daily").contains("explicit project"))
    }

    @Test fun `invalid write scope and project without workspace fail without side effects`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        repo.saveGlobalMd("user maintained preferences")
        listOf("global", "all", "unknown", "project").forEach { scope ->
            val result = MemoryTools.executeMemoryWrite("""{"content":"must not write","scope":"$scope"}""", repo)
            assertFalse(result.success)
            assertNull(result.scope)
            assertNull(result.projectWorkspacePath)
        }
        assertEquals("user maintained preferences", repo.loadGlobalMd())
        assertFalse(repo.getMemory("", "daily").contains("must not write"))
    }

    @Test fun `tool reads default to isolated project and preserve explicit legacy all`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        repo.saveGlobalMd("global preference")
        repo.writeMemory("daily side task")
        repo.writeProjectMemory(project, "project database fact")
        val auto = MemoryTools.executeMemoryGet("{}", repo, project)
        assertTrue(auto.success)
        assertTrue(auto.output.contains("project database fact"))
        assertTrue(auto.output.contains("global preference"))
        assertFalse(auto.output.contains("daily side task"))
        val all = MemoryTools.executeMemoryGet("""{"scope":"all"}""", repo, project)
        assertTrue(all.success)
        assertTrue(all.output.contains("global preference"))
        assertTrue(all.output.contains("daily side task"))
        assertFalse(all.output.contains("project database fact"))
        val outside = MemoryTools.executeMemoryGet("{}", repo)
        assertTrue(outside.output.contains("daily side task"))
        assertFalse(MemoryTools.executeMemoryGet("""{"scope":"project"}""", repo).success)
        assertFalse(MemoryTools.executeMemoryGet("""{"scope":"unknown"}""", repo, project).success)
    }

    @Test fun `provider neutral Anthropic and OpenAI schemas share descriptions and scopes`() {
        val definitions = AgentTools.makeAgentTools().associateBy { it.name }
        listOf("memory_write", "memory_get").forEach { name ->
            val definition = definitions.getValue(name)
            val anthropic = if (name == "memory_write") MemoryTools.memoryWriteToolDefinition() else MemoryTools.memoryGetToolDefinition()
            val openAI = if (name == "memory_write") MemoryTools.memoryWriteOpenAIDefinition() else MemoryTools.memoryGetOpenAIDefinition()
            assertEquals(definition.description, anthropic.getString("description"))
            assertEquals(definition.description, openAI.getJSONObject("function").getString("description"))
            val jsonScope = anthropic.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("scope")
            assertEquals(definition.parameters.getValue("scope").description, jsonScope.getString("description"))
            val jsonValues = jsonScope.getJSONArray("enum")
            assertEquals(definition.parameters.getValue("scope").enumValues, (0 until jsonValues.length()).map { jsonValues.getString(it) })
            assertTrue(anthropic.getJSONObject("input_schema").similar(openAI.getJSONObject("function").getJSONObject("parameters")))
        }
    }
}
