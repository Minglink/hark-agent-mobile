package com.openminis.app.rag.retrieval

import com.openminis.app.logging.AppLogger
import com.openminis.app.rag.chunking.DocumentChunk
import kotlin.math.sqrt

data class SearchResult(
    val chunk: DocumentChunk,
    val rrfScore: Float,
    val keywordScore: Float,
    val vectorScore: Float,
    val keywordRank: Int,
    val vectorRank: Int,
)

data class IndexedChunk(
    val chunk: DocumentChunk,
    val vector: FloatArray? = null,
)

/**
 * HybridRetrievalEngine provides dual-channel retrieval:
 * Channel 1: BM25 / Keyword frequency matching with breadcrumb weighting
 * Channel 2: Dense vector cosine similarity
 * Fusion: Reciprocal Rank Fusion (RRF) for robust multi-modal ranking.
 */
class HybridRetrievalEngine {
    companion object {
        private const val TAG = "HybridRetrievalEngine"
        private const val RRF_K = 60f
        const val MAX_CHUNKS_PER_ENGINE = 4000
    }

    private val index = mutableListOf<IndexedChunk>()

    @Synchronized
    fun addChunks(chunks: List<DocumentChunk>, vectors: List<FloatArray>? = null) {
        for (i in chunks.indices) {
            val v = if (vectors != null && i < vectors.size) vectors[i] else null
            index.add(IndexedChunk(chunks[i], v))
        }
        // Memory guard: Evict oldest chunks if exceeding cap to protect mobile heap
        while (index.size > MAX_CHUNKS_PER_ENGINE) {
            index.removeAt(0)
        }
        AppLogger.info(TAG, "Added ${chunks.size} chunks to index. Total chunks: ${index.size}")
    }

    @Synchronized
    fun clear() {
        index.clear()
    }

    fun size(): Int = index.size

    fun getAllChunks(): List<DocumentChunk> = index.map { it.chunk }

    /**
     * Executes hybrid retrieval combining keyword matching and vector similarity via RRF.
     */
    fun search(
        query: String,
        topK: Int = 5,
        queryVector: FloatArray? = null,
        keywordWeight: Float = 0.5f,
        vectorWeight: Float = 0.5f,
    ): List<SearchResult> {
        if (index.isEmpty() || query.isBlank()) return emptyList()

        val normalizedQuery = query.trim().lowercase()
        val queryTerms = normalizedQuery.split(Regex("""[\s,.:;!?/\\()\[\]{}'"]+""")).filter { it.length >= 2 }

        // --- Path 1: BM25 / Keyword Score ---
        val avgDocLen = (index.sumOf { it.chunk.content.length } / index.size.coerceAtLeast(1)).toFloat()
        val k1 = 1.2f
        val b = 0.75f

        val keywordScores = index.mapIndexed { idx, item ->
            val contentLower = item.chunk.content.lowercase()
            val breadcrumbLower = item.chunk.breadcrumb.lowercase()
            var score = 0f
            val docLen = item.chunk.content.length.toFloat()

            for (term in queryTerms) {
                // Term frequency in content
                val termCount = countOccurrences(contentLower, term)
                // Breadcrumb exact match receives high bonus
                val breadcrumbBonus = if (breadcrumbLower.contains(term)) 3f else 0f

                if (termCount > 0 || breadcrumbBonus > 0f) {
                    val tf = termCount.toFloat()
                    val bm25Term = (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * (docLen / avgDocLen)))
                    score += bm25Term + breadcrumbBonus
                }
            }
            idx to score
        }.sortedByDescending { it.second }

        // --- Path 2: Dense Vector Cosine Similarity ---
        val vectorScores = if (queryVector != null) {
            index.mapIndexed { idx, item ->
                val score = if (item.vector != null) {
                    cosineSimilarity(queryVector, item.vector)
                } else 0f
                idx to score
            }.sortedByDescending { it.second }
        } else {
            // If no dense query vector is supplied, fall back to character n-gram pseudo-semantic similarity
            index.mapIndexed { idx, item ->
                idx to jaccardCharSimilarity(normalizedQuery, item.chunk.content.lowercase().take(1000))
            }.sortedByDescending { it.second }
        }

        // --- Path 3: Reciprocal Rank Fusion (RRF) ---
        val keywordRankMap = mutableMapOf<Int, Int>() // chunk index -> 1-based rank
        val kwScoreMap = mutableMapOf<Int, Float>()
        keywordScores.forEachIndexed { rank, pair ->
            keywordRankMap[pair.first] = rank + 1
            kwScoreMap[pair.first] = pair.second
        }

        val vectorRankMap = mutableMapOf<Int, Int>() // chunk index -> 1-based rank
        val vecScoreMap = mutableMapOf<Int, Float>()
        vectorScores.forEachIndexed { rank, pair ->
            vectorRankMap[pair.first] = rank + 1
            vecScoreMap[pair.first] = pair.second
        }

        val combinedResults = index.indices.map { idx ->
            val kwRank = keywordRankMap[idx] ?: (index.size + 1)
            val vecRank = vectorRankMap[idx] ?: (index.size + 1)

            val kwScore = kwScoreMap[idx] ?: 0f
            val vecScore = vecScoreMap[idx] ?: 0f

            val rrfScore = (keywordWeight / (RRF_K + kwRank)) + (vectorWeight / (RRF_K + vecRank))

            SearchResult(
                chunk = index[idx].chunk,
                rrfScore = rrfScore,
                keywordScore = kwScore,
                vectorScore = vecScore,
                keywordRank = kwRank,
                vectorRank = vecRank,
            )
        }

        // Sort by RRF score descending and take topK
        return combinedResults
            .filter { it.keywordScore > 0f || it.vectorScore > 0.1f }
            .sortedByDescending { it.rrfScore }
            .take(topK)
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (idx != -1) {
            idx = text.indexOf(sub, idx)
            if (idx != -1) {
                count++
                idx += sub.length
            }
        }
        return count
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) (dot / denom).coerceIn(0f, 1f) else 0f
    }

    private fun jaccardCharSimilarity(s1: String, s2: String): Float {
        val set1 = s1.windowed(3, 1).toSet()
        val set2 = s2.windowed(3, 1).toSet()
        if (set1.isEmpty() || set2.isEmpty()) return 0f
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }
}
