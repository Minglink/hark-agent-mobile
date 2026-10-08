package com.openminis.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectSystemPromptCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun clearCache() { ProjectPromptManager.clearCache() }

    @Test
    fun `workspace SYSTEM file is discovered by the main loader and legacy facade`() {
        val workspace = temporary.newFolder()
        File(workspace, "SYSTEM.md").writeText("Workspace system rule\n\n  keep internal indentation")

        val info = ProjectPromptManager.loadProjectPrompt(workspace)!!
        assertEquals("SYSTEM.md", info.file.name)
        assertEquals(ProjectPromptMode.APPEND, info.mode)
        assertEquals("Workspace system rule\n\n  keep internal indentation", info.content)
        val legacy = com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!
        assertTrue(legacy.contains("WORKSPACE PROJECT INSTRUCTIONS (SYSTEM.md)"))
        assertTrue(legacy.contains(info.content))
    }

    @Test
    fun `SYSTEM override is parsed and suppresses global customization through the facade`() {
        val workspace = temporary.newFolder()
        File(workspace, "SYSTEM.md").writeText("---\nmode: \"override\"\n---\n\nPROJECT_OVERRIDE\nDo not alter this body.")
        val global = SystemPromptFile(SystemPromptMode.PREPEND, "GLOBAL_CUSTOMIZATION")

        assertEquals(ProjectPromptMode.OVERRIDE, ProjectPromptManager.loadProjectPrompt(workspace)!!.mode)
        val output = SystemPromptStore.injectionBlockWithWorkspace(global, workspace)!!
        assertTrue(output.contains("PROJECT_OVERRIDE\nDo not alter this body."))
        assertFalse(output.contains("GLOBAL_CUSTOMIZATION"))
        assertFalse(output.contains("mode: \"override\""))
    }

    @Test
    fun `SYSTEM append keeps global and workspace directives exactly once`() {
        val workspace = temporary.newFolder()
        File(workspace, "SYSTEM.md").writeText("---\nmode: append\n---\n\nPROJECT_APPEND")
        val output = SystemPromptStore.injectionBlockWithWorkspace(SystemPromptFile(SystemPromptMode.SUFFIX, "GLOBAL_CUSTOMIZATION"), workspace)!!

        assertTrue(output.indexOf("GLOBAL_CUSTOMIZATION") < output.indexOf("PROJECT_APPEND"))
        assertEquals(output.indexOf("PROJECT_APPEND"), output.lastIndexOf("PROJECT_APPEND"))
        assertEquals(output.indexOf("GLOBAL_CUSTOMIZATION"), output.lastIndexOf("GLOBAL_CUSTOMIZATION"))
    }

    @Test
    fun `dedicated project prompt remains ahead of workspace SYSTEM in both loaders`() {
        val workspace = temporary.newFolder()
        File(workspace, "SYSTEM.md").writeText("LOWER_PRIORITY_SYSTEM")
        File(workspace, ".hark").mkdirs()
        File(workspace, ".hark/PROJECT_PROMPT.md").writeText("DEDICATED_PROJECT")

        assertEquals("DEDICATED_PROJECT", ProjectPromptManager.loadProjectPrompt(workspace)!!.content)
        val facade = com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!
        assertTrue(facade.contains("DEDICATED_PROJECT"))
        assertFalse(facade.contains("LOWER_PRIORITY_SYSTEM"))
    }

    @Test
    fun `blank SYSTEM falls through to HARK conventions`() {
        val workspace = temporary.newFolder()
        File(workspace, "SYSTEM.md").writeText("  \n")
        File(workspace, "HARK.md").writeText("HARK_FALLBACK")
        assertEquals("HARK_FALLBACK", ProjectPromptManager.loadProjectPrompt(workspace)!!.content)
        assertTrue(com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!.contains("HARK_FALLBACK"))
    }

    @Test
    fun `external SYSTEM edit is visible to both loaders without clearing their cache`() {
        val workspace = temporary.newFolder()
        val file = File(workspace, "SYSTEM.md").apply { writeText("OLD_RULE") }
        val previousTime = Files.getLastModifiedTime(file.toPath())
        assertEquals("OLD_RULE", ProjectPromptManager.loadProjectPrompt(workspace)!!.content)
        assertTrue(com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!.contains("OLD_RULE"))

        file.writeText("NEW_LONGER_RULE")
        Files.setLastModifiedTime(file.toPath(), previousTime)

        assertEquals("NEW_LONGER_RULE", ProjectPromptManager.loadProjectPrompt(workspace)!!.content)
        val refreshed = com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!
        assertTrue(refreshed.contains("NEW_LONGER_RULE"))
        assertFalse(refreshed.contains("OLD_RULE"))
    }

    @Test
    fun `canonical and legacy candidate priorities remain distinct when RULES and SYSTEM coexist`() {
        val workspace = temporary.newFolder()
        File(workspace, ".hark").mkdirs()
        File(workspace, ".hark/RULES.md").writeText("CANONICAL_RULES")
        File(workspace, "SYSTEM.md").writeText("---\nmode: override\n---\n\nLEGACY_SYSTEM_OVERRIDE")

        val canonical = ProjectPromptManager.loadProjectPrompt(workspace)!!
        assertEquals("RULES.md", canonical.file.name)
        assertEquals("CANONICAL_RULES", canonical.content)
        val facade = com.openminis.app.agent.prompt.ProjectPromptManager.resolveProjectPrompt(workspace)!!
        assertTrue(facade.contains("LEGACY_SYSTEM_OVERRIDE"))
        assertFalse(facade.contains("CANONICAL_RULES"))

        // The workspace facade must use the selected SYSTEM file's override
        // mode, rather than borrowing APPEND from the canonical RULES result.
        val injected = SystemPromptStore.injectionBlockWithWorkspace(
            SystemPromptFile(SystemPromptMode.PREPEND, "GLOBAL_CUSTOMIZATION"), workspace,
        )!!
        assertTrue(injected.contains("LEGACY_SYSTEM_OVERRIDE"))
        assertFalse(injected.contains("GLOBAL_CUSTOMIZATION"))
        assertFalse(injected.contains("CANONICAL_RULES"))
    }
}
