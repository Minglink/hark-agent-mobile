package com.openminis.app.rag

import com.openminis.app.rag.chunking.DocumentChunk
import com.openminis.app.rag.retrieval.HybridRetrievalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridRetrievalEngineTest {

    private lateinit var engine: HybridRetrievalEngine

    @Before
    fun setUp() {
        engine = HybridRetrievalEngine()
    }

    @Test
    fun testKeywordAndBreadcrumbMatching() {
        val chunk1 = DocumentChunk(
            id = "1",
            filePath = "docs/shizuku.md",
            content = "Shizuku enables executing ADB commands via binder IPC without root privileges.",
            breadcrumb = "Hark > Device Automation > Shizuku",
            startLine = 1,
            endLine = 5,
            tokenEstimate = 20,
        )

        val chunk2 = DocumentChunk(
            id = "2",
            filePath = "docs/memory.md",
            content = "Hybrid search uses BM25 and vector embeddings combined with Reciprocal Rank Fusion.",
            breadcrumb = "Hark > RAG > Hybrid Search",
            startLine = 1,
            endLine = 5,
            tokenEstimate = 20,
        )

        engine.addChunks(listOf(chunk1, chunk2))

        val results = engine.search("Shizuku binder", topK = 5)
        assertTrue(results.isNotEmpty())
        assertEquals("1", results.first().chunk.id)
        assertTrue(results.first().keywordScore > 0f)
        assertTrue(results.first().rrfScore > 0f)
    }

    @Test
    fun testVectorCosineSimilarityMatching() {
        val chunk1 = DocumentChunk(
            id = "1",
            filePath = "vec1.txt",
            content = "Vector A representation",
            breadcrumb = "Vector A",
            startLine = 1,
            endLine = 1,
            tokenEstimate = 5,
        )

        val chunk2 = DocumentChunk(
            id = "2",
            filePath = "vec2.txt",
            content = "Vector B representation",
            breadcrumb = "Vector B",
            startLine = 1,
            endLine = 1,
            tokenEstimate = 5,
        )

        // Two orthogonal 3D vectors
        val vec1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val vec2 = floatArrayOf(0.0f, 1.0f, 0.0f)

        engine.addChunks(listOf(chunk1, chunk2), listOf(vec1, vec2))

        // Query vector aligns with vec1
        val queryVec = floatArrayOf(0.9f, 0.1f, 0.0f)
        val results = engine.search("Vector", topK = 5, queryVector = queryVec)

        assertTrue(results.isNotEmpty())
        assertEquals("1", results.first().chunk.id)
        assertTrue(results.first().vectorScore > 0.8f)
    }

    @Test
    fun testReciprocalRankFusionScoring() {
        val chunk1 = DocumentChunk(
            id = "1",
            filePath = "doc1.md",
            content = "First document content",
            breadcrumb = "Doc 1",
            startLine = 1,
            endLine = 1,
            tokenEstimate = 5,
        )

        val chunk2 = DocumentChunk(
            id = "2",
            filePath = "doc2.md",
            content = "Second document content",
            breadcrumb = "Doc 2",
            startLine = 1,
            endLine = 1,
            tokenEstimate = 5,
        )

        engine.addChunks(listOf(chunk1, chunk2))

        val results = engine.search("First", topK = 5)
        assertEquals(1, results.size)
        assertEquals("1", results.first().chunk.id)
        assertTrue(results.first().rrfScore > 0f)
    }

    @Test
    fun testFifoCapAndPerformance() {
        val chunks = (1..500).map { i ->
            DocumentChunk(
                id = "$i",
                filePath = "file_$i.kt",
                content = "fun function$i() { val x = $i; println(x) }",
                breadcrumb = "File $i > function$i",
                startLine = 1,
                endLine = 3,
                tokenEstimate = 10,
            )
        }
        engine.addChunks(chunks)
        val startTime = System.currentTimeMillis()
        val results = engine.search("function250", topK = 5)
        val duration = System.currentTimeMillis() - startTime

        assertTrue("Search took too long: ${duration}ms", duration < 1000)
        assertTrue(results.isNotEmpty())
        assertEquals("250", results.first().chunk.id)
    }
}
