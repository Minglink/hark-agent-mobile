package com.openminis.app.rag.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.rag.chunking.DocumentChunker
import com.openminis.app.rag.retrieval.HybridRetrievalEngine
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RagTools {
    const val TOOL_DOC_INDEX = "doc_index"
    const val TOOL_DOC_QUERY = "doc_query"
    private const val TAG = "RagTools"

    // Session-scoped retrieval engines bounded by LRU to prevent memory leak across sessions
    private val sessionEngines: MutableMap<String, HybridRetrievalEngine> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, HybridRetrievalEngine>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HybridRetrievalEngine>?): Boolean {
                    return size > 8
                }
            }
        )

    fun getEngineForSession(sessionId: String): HybridRetrievalEngine {
        synchronized(sessionEngines) {
            return sessionEngines.getOrPut(sessionId) { HybridRetrievalEngine() }
        }
    }

    fun clearSession(sessionId: String) {
        synchronized(sessionEngines) {
            sessionEngines.remove(sessionId)?.clear()
        }
    }

    fun indexDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_DOC_INDEX,
        description = "Index project technical documents and code files into the RAG memory store using intelligent hierarchical chunking. " +
            "Supports Markdown, source code files (.kt, .py, .ts, etc.), and text files. " +
            "Use this before searching large project documentation or codebases.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise 5-10 word summary of what is being indexed (e.g. 'Index project architecture docs')."),
            "path" to AgentToolParam("string", "Path to file or directory in workspace (e.g. /var/hark/workspace/docs or hark://workspace/README.md)."),
            "recursive" to AgentToolParam("boolean", "Whether to index subdirectories recursively (default: true)."),
            "extensions" to AgentToolParam("string", "Comma-separated list of file extensions to include (default: 'md,txt,kt,py,ts,js,json')."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "recursive", "extensions"),
    )

    fun queryDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_DOC_QUERY,
        description = "Perform hybrid search (BM25 keyword matching + semantic similarity via Reciprocal Rank Fusion) across previously indexed project documents. " +
            "Returns top matching document chunks with exact line numbers, breadcrumbs, and snippets.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Concise 5-10 word summary of what is being queried (e.g. 'Search Shizuku dispatch logic')."),
            "query" to AgentToolParam("string", "Natural language question, keyword query, or technical identifier to search for."),
            "top_k" to AgentToolParam("integer", "Maximum number of chunks to return (default: 5, max: 15)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "top_k"),
    )

    suspend fun executeIndex(
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", TOOL_DOC_INDEX)
            val rawPath = args.getString("path")
            val recursive = args.optBoolean("recursive", true)
            val extStr = args.optString("extensions", "md,txt,kt,py,ts,js,json")
            val allowedExts = extStr.split(",").map { it.trim().lowercase().removePrefix(".") }.toSet()

            val normalizedPath = if (rawPath.startsWith("hark://")) {
                "/var/hark/" + java.net.URLDecoder.decode(rawPath.removePrefix("hark://"), "UTF-8")
            } else rawPath

            val hostFile = PRootKernel.resolveSessionHostPath(sessionId, normalizedPath, context)
                ?: PRootKernel.resolveHostPath(normalizedPath)
                ?: return@withContext ToolExecutionResult("Error: Cannot resolve path '$rawPath'", false, toolTitle = toolTitle)

            if (!hostFile.exists()) {
                return@withContext ToolExecutionResult("Error: Path '$rawPath' does not exist", false, toolTitle = toolTitle)
            }

            val filesToIndex = mutableListOf<File>()
            if (hostFile.isFile) {
                filesToIndex.add(hostFile)
            } else {
                val walk = if (recursive) hostFile.walkTopDown() else hostFile.walk()
                walk.filter { it.isFile && allowedExts.contains(it.extension.lowercase()) }
                    .forEach { filesToIndex.add(it) }
            }

            val engine = getEngineForSession(sessionId)
            var totalChunksIndexed = 0
            var totalTokensEstimate = 0

            for (file in filesToIndex) {
                try {
                    val content = file.readText(Charsets.UTF_8)
                    val relPath = file.absolutePath.replace(hostFile.parentFile?.absolutePath ?: "", "").trimStart('/', '\\')
                    val chunks = DocumentChunker.chunkFile(relPath.ifBlank { file.name }, content)
                    engine.addChunks(chunks)
                    totalChunksIndexed += chunks.size
                    totalTokensEstimate += chunks.sumOf { it.tokenEstimate }
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "Error indexing file ${file.name}: ${t.message}")
                }
            }

            val summary = "Successfully indexed ${filesToIndex.size} files into $totalChunksIndexed chunks (~$totalTokensEstimate tokens) in RAG memory store."
            ToolExecutionResult(summary, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error in executeIndex", e)
            ToolExecutionResult("Error indexing documents: ${e.message}", false)
        }
    }

    suspend fun executeQuery(
        argsJson: String,
        sessionId: String,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", TOOL_DOC_QUERY)
            val query = args.getString("query")
            val topK = args.optInt("top_k", 5).coerceIn(1, 15)

            val engine = getEngineForSession(sessionId)
            if (engine.size() == 0) {
                return@withContext ToolExecutionResult(
                    output = "RAG index is currently empty for this session. Use 'doc_index' first to index files from workspace or docs.",
                    success = true,
                    toolTitle = toolTitle,
                )
            }

            val results = engine.search(query, topK = topK)
            if (results.isEmpty()) {
                return@withContext ToolExecutionResult(
                    output = "No matching document chunks found for query: '$query'. Try broader keywords or re-indexing.",
                    success = true,
                    toolTitle = toolTitle,
                )
            }

            val out = buildString {
                appendLine("Found ${results.size} relevant document chunks (Hybrid RRF ranking):\n")
                results.forEachIndexed { i, res ->
                    appendLine("### [${i + 1}] ${res.chunk.filePath} (Lines ${res.chunk.startLine}-${res.chunk.endLine})")
                    appendLine("- **Breadcrumb**: `${res.chunk.breadcrumb}`")
                    appendLine("- **RRF Score**: `${String.format("%.4f", res.rrfScore)}` (BM25 rank: #${res.keywordRank}, Vector rank: #${res.vectorRank})")
                    appendLine("```")
                    appendLine(res.chunk.content)
                    appendLine("```\n")
                }
            }

            ToolExecutionResult(out.trimEnd(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Error in executeQuery", e)
            ToolExecutionResult("Error querying documents: ${e.message}", false)
        }
    }
}
