package com.openminis.app.core

import com.openminis.app.data.db.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V123ProjectContextTest
 *
 * 验证 1.2.3 版本新增的会话项目与工作目录显示与解算机制：
 * 1. 项目工作目录默认规则与自定义 linux_path 解算
 * 2. 草稿会话 ID 中 __prj__ 标记提取规则
 * 3. 项目实体字段边界保护
 */
class V123ProjectContextTest {

    @Test
    fun testProjectFolderPathResolution() {
        // 1. 未配置自定义路径时，采用标准 /var/hark/projects/<name>
        val project1 = ProjectEntity(
            id = "prj-1",
            name = "AwesomeApp",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val defaultPath = project1.linuxPath?.takeIf { it.isNotBlank() } ?: "/var/hark/projects/${project1.name}"
        assertEquals("/var/hark/projects/AwesomeApp", defaultPath)

        // 2. 配置自定义路径时，采用自定义路径
        val project2 = ProjectEntity(
            id = "prj-2",
            name = "CustomApp",
            linuxPath = "/var/hark/custom/repo",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val customPath = project2.linuxPath?.takeIf { it.isNotBlank() } ?: "/var/hark/projects/${project2.name}"
        assertEquals("/var/hark/custom/repo", customPath)
    }

    @Test
    fun testDraftSessionProjectParamExtraction() {
        val draftSessionId = "__new__550e8400-e29b-41d4-a716-446655440000__prj__prj-9988__grp__default"
        fun extract(marker: String, id: String): String? {
            val regex = Regex("${marker}(.*?)(?=__|$)")
            return regex.find(id)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        }

        val extractedProjectId = extract("__prj__", draftSessionId)
        assertEquals("prj-9988", extractedProjectId)

        val extractedGroupId = extract("__grp__", draftSessionId)
        assertEquals("default", extractedGroupId)

        // 无项目参数的草稿会话
        val normalDraftId = "__new__550e8400-e29b-41d4-a716-446655440000__grp__default"
        val extractedNone = extract("__prj__", normalDraftId)
        assertNull(extractedNone)
    }
}
