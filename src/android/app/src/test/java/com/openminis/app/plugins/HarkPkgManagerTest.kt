package com.openminis.app.plugins

import com.openminis.app.plugins.harkpkg.HarkPkgManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HarkPkgManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createValidHarkPkgZip(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // 1. manifest.json
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifestJson = """
                {
                    "id": "com.test.extension",
                    "name": "Test Extension",
                    "version": "1.2.0",
                    "description": "A testing Hark extension",
                    "author": "Tester"
                }
            """.trimIndent()
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. prompt/coding_standard.md
            zos.putNextEntry(ZipEntry("prompt/coding_standard.md"))
            zos.write("Always write clean Kotlin code.".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. tools/dynamic_tools.json
            zos.putNextEntry(ZipEntry("tools/dynamic_tools.json"))
            val toolsJson = """
                {
                    "tools": [
                        {
                            "name": "custom_audit",
                            "description": "Performs code audit",
                            "parameters": {
                                "properties": {
                                    "target": { "type": "string", "description": "Path to audit" }
                                },
                                "required": ["target"]
                            }
                        }
                    ]
                }
            """.trimIndent()
            zos.write(toolsJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test
    fun testInstallAndLoadHarkPkg() {
        val targetDir = tempFolder.newFolder("plugins")
        val zipBytes = createValidHarkPkgZip()

        val pkg = HarkPkgManager.installPackage(ByteArrayInputStream(zipBytes), targetDir)
        assertNotNull(pkg)
        assertEquals("com.test.extension", pkg.manifest.id)
        assertEquals("1.2.0", pkg.manifest.version)
        assertEquals(1, pkg.systemPromptAddons.size)
        assertEquals("Always write clean Kotlin code.", pkg.systemPromptAddons.first())

        assertEquals(1, pkg.dynamicTools.size)
        val dynamicTool = pkg.dynamicTools.first()
        assertEquals("custom_audit", dynamicTool.name)
        assertTrue(dynamicTool.required.contains("target"))

        // Active addons
        val activeAddons = HarkPkgManager.activeSystemPromptAddons()
        assertTrue(activeAddons.contains("Always write clean Kotlin code."))
    }

    @Test(expected = SecurityException::class)
    fun testZipSlipRejection() {
        val targetDir = tempFolder.newFolder("plugins_sec")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("../../../evil.sh"))
            zos.write("echo evil".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        HarkPkgManager.installPackage(ByteArrayInputStream(bos.toByteArray()), targetDir)
    }

    @Test
    fun testScanAndLoadAll() {
        val targetDir = tempFolder.newFolder("plugins_scan")
        val zipBytes = createValidHarkPkgZip()
        HarkPkgManager.installPackage(ByteArrayInputStream(zipBytes), targetDir)

        // Clear in-memory state and rescan to simulate app restart
        val loaded = HarkPkgManager.scanAndLoadAll(targetDir)
        assertEquals(1, loaded.size)
        assertEquals("com.test.extension", loaded.first().manifest.id)
        assertNotNull(HarkPkgManager.getPackage("com.test.extension"))
    }
}
