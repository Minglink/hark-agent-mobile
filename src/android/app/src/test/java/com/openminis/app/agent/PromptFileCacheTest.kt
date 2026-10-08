package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class PromptFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `unchanged UTF8 source is parsed only once`() {
        val file = temporary.newFile("SYSTEM.md").apply { writeText("中文说明\n  keep indentation\n") }
        var parses = 0
        val cache = PromptFileCache<String> { _, source -> parses++; source }

        assertEquals(file.readText(), cache.read(file))
        assertEquals(file.readText(), cache.read(file))
        assertEquals(1, parses)
    }

    @Test
    fun `same length edits are observed when modification time changes`() {
        val file = temporary.newFile().apply { writeText("before") }
        val previousTime = Files.getLastModifiedTime(file.toPath())
        var parses = 0
        val cache = PromptFileCache<String> { _, source -> parses++; source }
        assertEquals("before", cache.read(file))

        file.writeText("after!")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(previousTime.toMillis() + 2_000))

        assertEquals("after!", cache.read(file))
        assertEquals(2, parses)
    }

    @Test
    fun `size changes invalidate the cache even with the previous timestamp`() {
        val file = temporary.newFile().apply { writeText("old") }
        val previousTime = Files.getLastModifiedTime(file.toPath())
        val cache = PromptFileCache<String> { _, source -> source }
        assertEquals("old", cache.read(file))

        file.writeText("a longer replacement")
        Files.setLastModifiedTime(file.toPath(), previousTime)

        assertEquals("a longer replacement", cache.read(file))
    }

    @Test
    fun `atomic replacement is detected despite equal size and timestamp`() {
        val target = temporary.newFile("prompt.md").apply { writeText("before") }
        val before = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java)
        var parses = 0
        val cache = PromptFileCache<String> { _, source -> parses++; source }
        assertEquals("before", cache.read(target))

        val replacement = temporary.newFile("replacement.md").apply { writeText("after!") }
        if (before.fileKey() == null) {
            // Make the identity fallback deterministic even on a filesystem
            // whose creation timestamps have coarse resolution.
            Files.setAttribute(replacement.toPath(), "basic:creationTime", FileTime.fromMillis(before.creationTime().toMillis() + 2_000))
        }
        Files.setLastModifiedTime(replacement.toPath(), before.lastModifiedTime())
        try {
            Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            assumeNoException("Filesystem does not support atomic replacement", e)
        }
        val after = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java)
        assertEquals(before.size(), after.size())
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
        assertTrue(
            "Replacement must have a distinct file identity or creation timestamp",
            before.fileKey() != after.fileKey() || before.creationTime() != after.creationTime(),
        )
        assertEquals("after!", cache.read(target))
        assertEquals(2, parses)
    }

    @Test
    fun `parse exceptions are not cached and can recover without a file edit`() {
        val file = temporary.newFile().apply { writeText("valid body") }
        var attempts = 0
        val cache = PromptFileCache<String> { _, source ->
            attempts++
            if (attempts == 1) error("Transient parser failure")
            source
        }

        assertNull(cache.read(file))
        assertEquals("valid body", cache.read(file))
        assertEquals(2, attempts)
    }

    @Test
    fun `null parse results are cached until the source changes`() {
        val file = temporary.newFile().apply { writeText("invalid") }
        var attempts = 0
        val cache = PromptFileCache<String> { _, source -> attempts++; source.takeIf { it.startsWith("valid") } }
        assertNull(cache.read(file))
        assertNull(cache.read(file))
        assertEquals(1, attempts)

        file.writeText("valid replacement")
        assertEquals("valid replacement", cache.read(file))
        assertEquals(2, attempts)
    }

    @Test
    fun `missing files and directories never return a stale value`() {
        val file = temporary.newFile("deleted.md").apply { writeText("old value") }
        val cache = PromptFileCache<String> { _, source -> source }
        assertEquals("old value", cache.read(file))
        Files.delete(file.toPath())
        assertNull(cache.read(file))
        Files.createDirectory(file.toPath())
        assertNull(cache.read(file))
        Files.delete(file.toPath())
        file.writeText("new value")
        assertEquals("new value", cache.read(file))
    }

    @Test
    fun `least recently used entry is evicted rather than the oldest insertion`() {
        val first = temporary.newFile("first").apply { writeText("one") }
        val second = temporary.newFile("second").apply { writeText("two") }
        val third = temporary.newFile("third").apply { writeText("three") }
        val reads = mutableMapOf<String, Int>()
        val cache = PromptFileCache<String>(capacity = 2) { file, source ->
            reads[file.name] = (reads[file.name] ?: 0) + 1
            source
        }
        cache.read(first)
        cache.read(second)
        cache.read(first) // Keep first hot before adding third.
        cache.read(third)
        cache.read(first)
        cache.read(second)

        assertEquals(mapOf("first" to 1, "second" to 2, "third" to 1), reads)
    }

    @Test
    fun `explicit invalidation and clearing force a fresh parse`() {
        val file = temporary.newFile().apply { writeText("body") }
        var parses = 0
        val cache = PromptFileCache<String> { _, source -> parses++; source }
        cache.read(file)
        cache.invalidate(file)
        cache.read(file)
        cache.clear()
        cache.read(file)
        assertEquals(3, parses)
    }

    @Test
    fun `zero capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PromptFileCache<String>(0) { _, source -> source } }
    }
}
