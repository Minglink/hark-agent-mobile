package com.openminis.app.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Bounded parsed-file cache. Filesystem edits are checked on every read, including atomic replacement. */
internal class PromptFileCache<T>(
    private val capacity: Int = 64,
    private val parse: (File, String) -> T?,
) {
    // Windows may expose no fileKey; creation time still distinguishes a
    // replacement file whose editor preserved both mtime and byte count.
    private data class Stamp(val modified: FileTime, val size: Long, val identity: Any?, val created: FileTime)
    private data class Entry<T>(val stamp: Stamp, val value: T?)
    private val entries = object : LinkedHashMap<String, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>?): Boolean =
            size > capacity
    }

    init { require(capacity > 0) }

    @Synchronized
    fun read(file: File): T? {
        val key = file.absolutePath
        val before = stamp(file) ?: run { entries.remove(key); return null }
        entries[key]?.takeIf { it.stamp == before }?.let { return it.value }
        return try {
            val source = file.readText(Charsets.UTF_8)
            // Do not retain a snapshot observed during an in-place edit.
            if (stamp(file) != before) {
                entries.remove(key)
                return null
            }
            val value = parse(file, source)
            entries[key] = Entry(before, value)
            value
        } catch (_: Exception) {
            entries.remove(key)
            null
        }
    }

    @Synchronized
    fun invalidate(file: File) { entries.remove(file.absolutePath) }

    @Synchronized
    fun clear() { entries.clear() }

    private fun stamp(file: File): Stamp? = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        if (attrs.isRegularFile) Stamp(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey(), attrs.creationTime()) else null
    } catch (_: Exception) { null }
}
