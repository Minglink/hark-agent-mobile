package com.openminis.app.data

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectMemoryScopeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun fixture(): Triple<MemoryRepository, File, File> {
        val global = temporaryFolder.newFolder()
        val project = temporaryFolder.newFolder()
        val repo = MemoryRepository(global)
        repo.saveGlobalMd("Preference: use Chinese for responses")
        repo.writeMemory("Unrelated daily task: book a flight")
        repo.writeProjectMemory(project, "Project uses PostgreSQL")
        return Triple(repo, project, global)
    }

    @Test fun `project default searches project and global preferences without daily logs`() {
        val (repo, project) = fixture()
        val result = repo.getMemory("", workspaceDir = project)
        assertTrue(result.contains("PostgreSQL"))
        assertTrue(result.contains("Chinese"))
        assertFalse(result.contains("book a flight"))
    }

    @Test fun `empty project never falls back to unrelated daily logs`() {
        val (repo) = fixture()
        val result = repo.getMemory("", workspaceDir = temporaryFolder.newFolder())
        assertTrue(result.contains("Chinese"))
        assertFalse(result.contains("PostgreSQL"))
        assertFalse(result.contains("book a flight"))
    }

    @Test fun `outside a project default retains global and daily search`() {
        val (repo) = fixture()
        val result = repo.getMemory("")
        assertTrue(result.contains("Chinese"))
        assertTrue(result.contains("book a flight"))
        assertFalse(result.contains("PostgreSQL"))
    }

    @Test fun `explicit project global daily and legacy all have distinct scopes`() {
        val (repo, project) = fixture()
        val projectResult = repo.getMemory("", "project", project)
        assertTrue(projectResult.contains("PostgreSQL"))
        assertFalse(projectResult.contains("Chinese"))
        assertFalse(projectResult.contains("book a flight"))

        val globalResult = repo.getMemory("", "global", project)
        assertTrue(globalResult.contains("Chinese"))
        assertFalse(globalResult.contains("PostgreSQL"))
        assertFalse(globalResult.contains("book a flight"))

        val dailyResult = repo.getMemory("", "daily", project)
        assertTrue(dailyResult.contains("book a flight"))
        assertFalse(dailyResult.contains("Chinese"))
        assertFalse(dailyResult.contains("PostgreSQL"))

        val legacyAll = repo.getMemory("", "all", project)
        assertTrue(legacyAll.contains("Chinese"))
        assertTrue(legacyAll.contains("book a flight"))
        assertFalse(legacyAll.contains("PostgreSQL"))
    }

    @Test fun `project keyword search retrieves knowledge beyond prompt preview`() {
        val (repo, project) = fixture()
        repo.writeProjectMemory(project, (1..400).joinToString("\n") { "Recent context line $it" })
        val fragment = repo.loadProjectMemoryFragment(project)!!
        assertFalse(fragment.contains("PostgreSQL"))
        assertTrue(fragment.contains("scope=project"))
        val result = repo.getMemory("postgresql", "project", project)
        assertTrue(result.contains("Project uses PostgreSQL"))
        assertFalse(result.contains("book a flight"))
    }

    @Test fun `one project cannot retrieve another project memory`() {
        val (repo, project) = fixture()
        val otherProject = temporaryFolder.newFolder()
        repo.writeProjectMemory(otherProject, "Other project uses MongoDB")
        assertFalse(repo.getMemory("", "project", project).contains("MongoDB"))
        assertFalse(repo.getMemory("", "project", otherProject).contains("PostgreSQL"))
    }

    @Test fun `invalid and missing project scopes cannot fall back to daily logs`() {
        val (repo) = fixture()
        assertTrue(repo.getMemory("", "project").startsWith("Error:"))
        assertTrue(repo.getMemory("", "unknown").startsWith("Error:"))
    }

    @Test fun `concurrent repository instances preserve every project entry`() {
        val global = temporaryFolder.newFolder()
        val project = temporaryFolder.newFolder()
        val repositories = listOf(MemoryRepository(global), MemoryRepository(global))
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 40).map { index ->
                executor.submit<String> {
                    repositories[index % 2].writeProjectMemory(project, "unique entry [$index] 中文")
                }
            }
            futures.forEach { assertTrue(it.get(15, TimeUnit.SECONDS).startsWith("Project memory saved")) }
        } finally {
            executor.shutdownNow()
        }
        val content = File(project, ".hark/memory/PROJECT.md").readText(Charsets.UTF_8)
        assertEquals(40, Regex("<!--").findAll(content).count())
        (0 until 40).forEach { assertTrue(content.contains("unique entry [$it] 中文")) }
        assertFalse(File(project, ".hark/memory").listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun `project entry edit and revoke use captured workspace and never daily fallback`() {
        val (repo, project) = fixture()
        val other = temporaryFolder.newFolder()
        repo.writeMemory("identical note")
        repo.writeProjectMemory(project, "identical note")
        repo.writeProjectMemory(other, "identical note")

        assertTrue(repo.replaceEntryBody("identical note", "edited project note", project) is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.getMemory("edited project", "project", project).contains("edited project note"))
        assertTrue(repo.getMemory("identical", "daily").contains("identical note"))
        assertTrue(repo.getMemory("identical", "project", other).contains("identical note"))
        assertTrue(repo.revokeEntry("edited project note", project) is MemoryRepository.EntryMutationResult.Success)
        assertEquals(MemoryRepository.EntryMutationResult.NotFound, repo.revokeEntry("identical note", project))
        assertTrue(repo.getMemory("identical", "daily").contains("identical note"))
        // Existing no-workspace callers still edit/revoke only recent daily logs.
        assertTrue(repo.replaceEntryBody("identical note", "edited daily note") is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.revokeEntry("edited daily note") is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.getMemory("identical", "project", other).contains("identical note"))
    }

    @Test fun `project mutations preserve UTF8 BOM at the start and non ASCII text`() {
        val global = temporaryFolder.newFolder()
        val project = temporaryFolder.newFolder()
        val repo = MemoryRepository(global)
        val file = File(project, ".hark/memory/PROJECT.md")
        file.parentFile!!.mkdirs()
        file.writeText("\uFEFF<!-- 2026-01-01 12:00:00 -->\n保留知识🙂\n\n", Charsets.UTF_8)
        assertTrue(repo.writeProjectMemory(project, "新增中文").startsWith("Project memory saved"))
        assertTrue(repo.replaceEntryBody("新增中文", "修改中文", project) is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.revokeEntry("修改中文", project) is MemoryRepository.EntryMutationResult.Success)
        val content = file.readText(Charsets.UTF_8)
        assertTrue(content.startsWith('\uFEFF'))
        assertEquals(1, content.count { it == '\uFEFF' })
        assertTrue(content.contains("保留知识🙂"))
    }

    @Test fun `failed project writes preserve obstructing files and report failure`() {
        val repo = MemoryRepository(temporaryFolder.newFolder())
        val project = temporaryFolder.newFolder()
        val obstruction = File(project, ".hark")
        obstruction.writeText("preserve existing bytes 中文", Charsets.UTF_8)
        assertTrue(repo.writeProjectMemory(project, "new note").startsWith("Error"))
        assertEquals("preserve existing bytes 中文", obstruction.readText(Charsets.UTF_8))
        val missing = File(temporaryFolder.root, "missing-workspace")
        assertTrue(repo.writeProjectMemory(missing, "new note").startsWith("Error"))
        assertFalse(missing.exists())
    }
}
