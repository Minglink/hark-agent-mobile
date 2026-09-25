package com.openminis.app.data

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ProjectMemoryIsolationTest
 *
 * 验证项目专属记忆隔离机制：
 * 1. 写入项目专属记忆 (.hark/memory/PROJECT.md)
 * 2. 项目记忆与全局记忆彻底隔离，不同项目工作区之间互不串台
 * 3. loadProjectMemoryFragment 生成格式化的 <project_memory> XML 块
 */
class ProjectMemoryIsolationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWriteAndLoadProjectMemory() {
        val globalDir = tempFolder.newFolder("global_mem")
        val repo = MemoryRepository(globalDir)

        val projectDir = tempFolder.newFolder("project_alpha")

        // 初始无记忆
        assertNull(repo.loadProjectMemoryFragment(projectDir))

        // 写入项目记忆
        val result = repo.writeProjectMemory(projectDir, "Database schema: users table has uuid and role.")
        assertTrue(result.contains("saved to PROJECT.md"))

        val targetFile = File(projectDir, ".hark/memory/PROJECT.md")
        assertTrue(targetFile.exists())
        val rawText = targetFile.readText()
        assertTrue(rawText.contains("Database schema: users table has uuid and role."))

        // 读取记忆注入块
        val fragment = repo.loadProjectMemoryFragment(projectDir)
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("<project_memory>"))
        assertTrue(fragment.contains("Database schema: users table has uuid and role."))
        assertTrue(fragment.contains("</project_memory>"))
    }

    @Test
    fun testMemoryIsolationBetweenProjects() {
        val globalDir = tempFolder.newFolder("global_mem_iso")
        val repo = MemoryRepository(globalDir)

        val projectA = tempFolder.newFolder("project_A")
        val projectB = tempFolder.newFolder("project_B")

        // 写入项目 A 专属信息
        repo.writeProjectMemory(projectA, "Project A uses PostgreSQL and Rust.")

        // 写入项目 B 专属信息
        repo.writeProjectMemory(projectB, "Project B uses MongoDB and Python.")

        val memA = repo.loadProjectMemoryFragment(projectA)
        val memB = repo.loadProjectMemoryFragment(projectB)

        // 验证 A 只有 A 的记忆，绝对不含 B 的内容
        assertNotNull(memA)
        assertTrue(memA!!.contains("PostgreSQL and Rust"))
        assertTrue(!memA.contains("MongoDB and Python"))

        // 验证 B 只有 B 的记忆，绝对不含 A 的内容
        assertNotNull(memB)
        assertTrue(memB!!.contains("MongoDB and Python"))
        assertTrue(!memB.contains("PostgreSQL and Rust"))
    }
}
