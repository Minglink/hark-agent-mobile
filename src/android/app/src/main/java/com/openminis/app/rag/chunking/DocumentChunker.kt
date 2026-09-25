package com.openminis.app.rag.chunking

import java.util.UUID

data class DocumentChunk(
    val id: String,
    val filePath: String,
    val content: String,
    val breadcrumb: String,
    val startLine: Int,
    val endLine: Int,
    val tokenEstimate: Int,
)

object DocumentChunker {
    private const val MAX_CHUNK_CHARS = 1200
    private const val MIN_CHUNK_CHARS = 200

    fun chunkFile(filePath: String, text: String): List<DocumentChunk> {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> chunkMarkdown(filePath, text)
            "kt", "java", "py", "ts", "js", "rs", "go", "c", "cpp" -> chunkCode(filePath, text)
            else -> chunkSliding(filePath, text)
        }
    }

    /**
     * Markdown hierarchy chunking: Preserves header hierarchy breadcrumbs and sections.
     */
    fun chunkMarkdown(filePath: String, text: String): List<DocumentChunk> {
        val lines = text.lines()
        val chunks = mutableListOf<DocumentChunk>()
        val headerStack = mutableListOf<Pair<Int, String>>() // (level, title)

        var currentChunkLines = mutableListOf<String>()
        var chunkStartLine = 1

        fun flushChunk(endLine: Int) {
            if (currentChunkLines.isEmpty()) return
            val content = currentChunkLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                val breadcrumb = headerStack.joinToString(" > ") { it.second }.ifEmpty { filePath.substringAfterLast('/') }
                chunks.add(
                    DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        filePath = filePath,
                        content = content,
                        breadcrumb = breadcrumb,
                        startLine = chunkStartLine,
                        endLine = endLine,
                        tokenEstimate = (content.length / 4).coerceAtLeast(1),
                    )
                )
            }
            currentChunkLines = mutableListOf()
            chunkStartLine = endLine + 1
        }

        val headerRegex = Regex("""^(#{1,6})\s+(.*)$""")

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1
            val match = headerRegex.find(line.trim())

            if (match != null) {
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()

                // Flush previous chunk if non-empty and at/above MIN_CHUNK_CHARS or transitioning between sections
                val prevContent = currentChunkLines.joinToString("\n").trim()
                if (prevContent.isNotBlank() && (prevContent.length >= MIN_CHUNK_CHARS || headerStack.any { it.first >= level })) {
                    flushChunk(lineNumber - 1)
                }

                // Update header stack: pop equal or deeper levels
                while (headerStack.isNotEmpty() && headerStack.last().first >= level) {
                    headerStack.removeAt(headerStack.lastIndex)
                }
                headerStack.add(level to title)
            }

            currentChunkLines.add(line)

            val currentLen = currentChunkLines.sumOf { it.length }
            if (currentLen >= MAX_CHUNK_CHARS) {
                flushChunk(lineNumber)
            }
        }

        if (currentChunkLines.isNotEmpty()) {
            flushChunk(lines.size)
        }

        return chunks
    }

    /**
     * Code syntax-aware chunking: splits by top-level declarations (class, fun, def).
     */
    fun chunkCode(filePath: String, text: String): List<DocumentChunk> {
        val lines = text.lines()
        val chunks = mutableListOf<DocumentChunk>()
        var currentChunkLines = mutableListOf<String>()
        var chunkStartLine = 1
        var currentScopeName = filePath.substringAfterLast('/')

        val declRegex = Regex("""^(class|interface|fun|def|object|struct|enum\s+class)\s+([A-Za-z0-9_]+)""")

        fun flush(endLine: Int) {
            if (currentChunkLines.isEmpty()) return
            val content = currentChunkLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                chunks.add(
                    DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        filePath = filePath,
                        content = content,
                        breadcrumb = "$filePath :: $currentScopeName",
                        startLine = chunkStartLine,
                        endLine = endLine,
                        tokenEstimate = (content.length / 4).coerceAtLeast(1),
                    )
                )
            }
            currentChunkLines = mutableListOf()
            chunkStartLine = endLine + 1
        }

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1
            val match = declRegex.find(line.trim())
            if (match != null) {
                if (currentChunkLines.sumOf { it.length } >= MIN_CHUNK_CHARS) {
                    flush(lineNumber - 1)
                }
                currentScopeName = match.groupValues[2]
            }

            currentChunkLines.add(line)

            if (currentChunkLines.sumOf { it.length } >= MAX_CHUNK_CHARS) {
                flush(lineNumber)
            }
        }

        if (currentChunkLines.isNotEmpty()) {
            flush(lines.size)
        }

        return chunks
    }

    /**
     * Sliding window chunking with overlap for generic prose / logs.
     */
    fun chunkSliding(filePath: String, text: String): List<DocumentChunk> {
        val lines = text.lines()
        val chunks = mutableListOf<DocumentChunk>()
        var currentLines = mutableListOf<String>()
        var chunkStartLine = 1

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1
            currentLines.add(line)
            val len = currentLines.sumOf { it.length }

            if (len >= MAX_CHUNK_CHARS) {
                val content = currentLines.joinToString("\n")
                chunks.add(
                    DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        filePath = filePath,
                        content = content,
                        breadcrumb = filePath.substringAfterLast('/'),
                        startLine = chunkStartLine,
                        endLine = lineNumber,
                        tokenEstimate = (content.length / 4).coerceAtLeast(1),
                    )
                )
                // Overlap: keep last 20% of lines for next chunk
                val overlapCount = (currentLines.size * 0.2).toInt().coerceAtLeast(1)
                currentLines = currentLines.takeLast(overlapCount).toMutableList()
                chunkStartLine = (lineNumber - overlapCount + 1).coerceAtLeast(1)
            }
        }

        if (currentLines.isNotEmpty()) {
            val content = currentLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                chunks.add(
                    DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        filePath = filePath,
                        content = content,
                        breadcrumb = filePath.substringAfterLast('/'),
                        startLine = chunkStartLine,
                        endLine = lines.size,
                        tokenEstimate = (content.length / 4).coerceAtLeast(1),
                    )
                )
            }
        }

        return chunks
    }
}
