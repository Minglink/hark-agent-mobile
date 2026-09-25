package com.openminis.app.rag

import com.openminis.app.rag.chunking.DocumentChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun testMarkdownHierarchyChunking() {
        val markdown = """
            # Architecture Guide
            This is the overview of the Hark system architecture.
            
            ## Device Control
            Detailed information about Shizuku and Accessibility subsystems.
            
            ### Shizuku Subsystem
            Shizuku provides elevated ADB capabilities without requiring root.
            It uses IPC Binder communication to send low-level input events.
            
            ### Shower Virtual Display
            Shower creates an offscreen VirtualDisplay for silent background execution.
            
            ## Plugin System
            HarkPkg defines the extension specification.
        """.trimIndent()

        val chunks = DocumentChunker.chunkMarkdown("docs/architecture.md", markdown)
        assertTrue("Expected multiple chunks from hierarchical document", chunks.size >= 2)

        // Check breadcrumbs
        val shizukuChunk = chunks.find { it.content.contains("Shizuku Subsystem") || it.content.contains("elevated ADB") }
        assertTrue(shizukuChunk != null)
        assertTrue(
            "Breadcrumb should reflect hierarchy",
            shizukuChunk!!.breadcrumb.contains("Architecture Guide") || shizukuChunk.breadcrumb.contains("Device Control")
        )

        // Check line bounds
        assertTrue(shizukuChunk.startLine > 0)
        assertTrue(shizukuChunk.endLine >= shizukuChunk.startLine)
    }

    @Test
    fun testCodeAwareChunking() {
        val kotlinCode = """
            package com.example.app
            
            class DeviceManager {
                fun start() {
                    println("Starting device")
                }
            }
            
            class NetworkClient {
                fun connect() {
                    println("Connecting...")
                }
            }
        """.trimIndent()

        val chunks = DocumentChunker.chunkCode("DeviceManager.kt", kotlinCode)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.breadcrumb.contains("DeviceManager") || it.breadcrumb.contains("NetworkClient") })
    }

    @Test
    fun testSlidingWindowChunkingWithOverlap() {
        val longText = (1..50).joinToString("\n") { "Line $it: Lorem ipsum dolor sit amet, consectetur adipiscing elit." }
        val chunks = DocumentChunker.chunkSliding("log.txt", longText)
        assertTrue(chunks.isNotEmpty())
        // Verify line ordering
        for (c in chunks) {
            assertTrue(c.startLine <= c.endLine)
        }
    }
}
