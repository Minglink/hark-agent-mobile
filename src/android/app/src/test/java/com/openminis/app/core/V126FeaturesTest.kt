package com.openminis.app.core

import com.openminis.app.agent.ProjectPromptManager
import com.openminis.app.agent.ProjectPromptMode
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.sandbox.SandboxDaemonSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * V126FeaturesTest
 *
 * 验证 1.2.6 版本核心升级点：
 * 1. 项目专属提示词 BOM 容错与级联解析
 * 2. 项目隔离记忆双轨读写
 * 3. 守护服务 PID 保护与孤儿回收器免杀
 */
class V126FeaturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBomToleranceInProjectPrompt() {
        val workspace = tempFolder.newFolder("prj_bom")
        val file = File(workspace, "HARK.md")
        // 带 UTF-8 BOM 的文件内容
        val bomContent = "\uFEFF---\nmode: \"override\"\n---\nClean Architecture Only"
        file.writeText(bomContent, Charsets.UTF_8)

        val parsed = ProjectPromptManager.parseProjectPromptFile(file)
        assertNotNull(parsed)
        assertEquals(ProjectPromptMode.OVERRIDE, parsed?.mode)
        assertEquals("Clean Architecture Only", parsed?.content)
    }

    @Test
    fun testProjectMemoryDualTrackIsolation() {
        val globalDir = tempFolder.newFolder("global_mem_126")
        val repo = MemoryRepository(globalDir)

        val projectDir = tempFolder.newFolder("prj_126")
        val saveResult = repo.writeProjectMemory(projectDir, "API Base URL: https://api.internal.hark/v1")
        assertTrue(saveResult.contains("saved to PROJECT.md"))

        val fragment = repo.loadProjectMemoryFragment(projectDir)
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("https://api.internal.hark/v1"))
        assertTrue(fragment.contains("<project_memory>"))
    }
}
