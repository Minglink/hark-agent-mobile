package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SystemPromptFileCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `UTF8 BOM does not disable global or project frontmatter`() {
        val source = "\uFEFF---\nmode: prepend\n---\n\n中文规则\n  keep indentation"
        val globalFile = temporary.newFile("SYSTEM.md").apply { writeText(source) }
        val global = SystemPromptStore.loadFile(globalFile)!!
        assertEquals(SystemPromptMode.PREPEND, global.mode)
        assertTrue(SystemPromptComposer.composeBase("BUILT_IN", global, null, "test")
            .startsWith(SystemPromptStore.injectionBlock(global)!!))
        assertTrue(global.body.contains("中文规则\n  keep indentation"))

        val projectFile = temporary.newFile("PROJECT_PROMPT.md").apply {
            writeText(source.replace("prepend", "override"))
        }
        val project = ProjectPromptManager.parseProjectPromptFile(projectFile)!!
        assertEquals(ProjectPromptMode.OVERRIDE, project.mode)
        assertEquals("中文规则\n  keep indentation", project.content)
    }

    @Test fun `external mode edits and deletion immediately change the composed global prompt`() {
        val file = temporary.newFile("SYSTEM.md").apply {
            writeText("---\nmode: prepend\n---\n\nCUSTOM_RULE")
        }
        val previousTime = Files.getLastModifiedTime(file.toPath())
        assertTrue(SystemPromptComposer.composeBase("BUILT_IN", SystemPromptStore.loadFile(file), null, "test")
            .contains("CUSTOM_RULE"))

        file.writeText("---\nmode: off\n---\n\nCUSTOM_RULE")
        Files.setLastModifiedTime(file.toPath(), previousTime)
        val inactive = SystemPromptStore.loadFile(file)!!
        assertEquals(SystemPromptMode.OFF, inactive.mode)
        assertEquals("BUILT_IN", SystemPromptComposer.composeBase("BUILT_IN", inactive, null, "test"))

        Files.delete(file.toPath())
        assertNull(SystemPromptStore.loadFile(file))
        assertFalse(SystemPromptComposer.composeBase("BUILT_IN", SystemPromptStore.loadFile(file), null, "test")
            .contains("CUSTOM_RULE"))
    }
}
