package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ProjectPromptManagerTest
 *
 * 验证项目专属系统提示词管理器：
 * 1. 规约文件探测优先级 (.hark/PROJECT_PROMPT.md > HARK.md > CLAUDE.md)
 * 2. YAML Frontmatter 解析 (mode: "override" vs "append")
 * 3. <project_scope> 格式化注入块组装
 * 4. 项目规约持久化保存
 */
class ProjectPromptManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPriorityResolution() {
        val workspace = tempFolder.newFolder("project_alpha")

        // 1. 无任何文件时返回 null
        assertNull(ProjectPromptManager.loadProjectPrompt(workspace))

        // 2. 存在 CLAUDE.md
        val claudeFile = File(workspace, "CLAUDE.md")
        claudeFile.writeText("Follow Claude guidelines", Charsets.UTF_8)
        val loadedClaude = ProjectPromptManager.loadProjectPrompt(workspace)
        assertNotNull(loadedClaude)
        assertEquals("CLAUDE.md", loadedClaude?.file?.name)
        assertEquals("Follow Claude guidelines", loadedClaude?.content)

        // 3. 存在更高优先级的 HARK.md，应覆盖 CLAUDE.md
        val harkFile = File(workspace, "HARK.md")
        harkFile.writeText("Follow Hark project rules", Charsets.UTF_8)
        val loadedHark = ProjectPromptManager.loadProjectPrompt(workspace)
        assertNotNull(loadedHark)
        assertEquals("HARK.md", loadedHark?.file?.name)
        assertEquals("Follow Hark project rules", loadedHark?.content)

        // 4. 存在最高优先级的 .hark/PROJECT_PROMPT.md，应优先选用
        val harkDir = File(workspace, ".hark").also { it.mkdirs() }
        val promptFile = File(harkDir, "PROJECT_PROMPT.md")
        promptFile.writeText("Top priority project prompt", Charsets.UTF_8)
        val loadedTop = ProjectPromptManager.loadProjectPrompt(workspace)
        assertNotNull(loadedTop)
        assertEquals("PROJECT_PROMPT.md", loadedTop?.file?.name)
        assertEquals("Top priority project prompt", loadedTop?.content)
    }

    @Test
    fun testFrontmatterModeOverrideAndAppend() {
        val workspace = tempFolder.newFolder("project_beta")
        val fileOverride = File(workspace, "HARK.md")
        fileOverride.writeText(
            """
            ---
            mode: "override"
            ---
            Strict override directives.
            """.trimIndent(),
            Charsets.UTF_8
        )

        val parsedOverride = ProjectPromptManager.parseProjectPromptFile(fileOverride)
        assertNotNull(parsedOverride)
        assertEquals(ProjectPromptMode.OVERRIDE, parsedOverride?.mode)
        assertEquals("Strict override directives.", parsedOverride?.content)

        // Append mode
        val fileAppend = File(workspace, "CLAUDE.md")
        fileAppend.writeText(
            """
            ---
            mode: "append"
            ---
            Additional rules.
            """.trimIndent(),
            Charsets.UTF_8
        )
        val parsedAppend = ProjectPromptManager.parseProjectPromptFile(fileAppend)
        assertNotNull(parsedAppend)
        assertEquals(ProjectPromptMode.APPEND, parsedAppend?.mode)
        assertEquals("Additional rules.", parsedAppend?.content)

        // No frontmatter defaults to APPEND
        val fileNoFm = File(workspace, "RAW.md")
        fileNoFm.writeText("Plain content without frontmatter", Charsets.UTF_8)
        val parsedNoFm = ProjectPromptManager.parseProjectPromptFile(fileNoFm)
        assertNotNull(parsedNoFm)
        assertEquals(ProjectPromptMode.APPEND, parsedNoFm?.mode)
        assertEquals("Plain content without frontmatter", parsedNoFm?.content)
    }

    @Test
    fun testRenderPromptBlock() {
        val workspace = tempFolder.newFolder("project_gamma")
        val file = File(workspace, "PROJECT_PROMPT.md")
        val info = ProjectPromptInfo(
            file = file,
            mode = ProjectPromptMode.OVERRIDE,
            content = "Always write clean Kotlin code."
        )

        val rendered = ProjectPromptManager.renderPromptBlock(info, "MyAwesomeProject")
        assertTrue(rendered.contains("<project_scope name=\"MyAwesomeProject\" file=\"PROJECT_PROMPT.md\">"))
        assertTrue(rendered.contains("Always write clean Kotlin code."))
        assertTrue(rendered.contains("</project_scope>"))
    }

    @Test
    fun testSaveProjectPrompt() {
        val workspace = tempFolder.newFolder("project_delta")
        val saved = ProjectPromptManager.saveProjectPrompt(
            workspaceDir = workspace,
            content = "Architecture: MVI + Clean Architecture",
            mode = ProjectPromptMode.OVERRIDE
        )
        assertTrue(saved)

        val targetFile = File(workspace, ".hark/PROJECT_PROMPT.md")
        assertTrue(targetFile.exists())

        val reloaded = ProjectPromptManager.loadProjectPrompt(workspace)
        assertNotNull(reloaded)
        assertEquals(ProjectPromptMode.OVERRIDE, reloaded?.mode)
        assertEquals("Architecture: MVI + Clean Architecture", reloaded?.content)
    }
}
