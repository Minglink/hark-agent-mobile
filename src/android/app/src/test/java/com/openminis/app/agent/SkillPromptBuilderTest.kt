package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class SkillPromptBuilderTest {
    private val day = 24L * 3600 * 1000
    private val now = 50 * day

    private fun skill(id: String, updatedAt: Long = now - 20 * day, useCount: Double = 0.0, bundled: Boolean = false) =
        SkillPromptBuilder.Entry(id, "Name $id", "Description $id", updatedAt, useCount, bundled)

    private fun disclosedSkills(catalog: String): List<Element> {
        val xml = catalog.substringAfter("<available_skills>").substringBefore("</available_skills>")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader("<available_skills>$xml</available_skills>")))
        val nodes = document.getElementsByTagName("skill")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun ids(catalog: String) = disclosedSkills(catalog).map {
        it.getElementsByTagName("path").item(0).textContent.removePrefix("/var/hark/skills/").removeSuffix("/SKILL.md")
    }

    @Test
    fun `empty skill catalog produces no prompt fragment`() {
        assertNull(SkillPromptBuilder.catalog(emptyList(), now))
    }

    @Test
    fun `small catalog is ordered by recency with stable identifier ties`() {
        val entries = listOf(skill("older", now - day), skill("b", now), skill("a", now))
        assertEquals(listOf("a", "b", "older"), ids(SkillPromptBuilder.catalog(entries, now)!!))
    }

    @Test
    fun `large catalog allocates bundled then ten recent then frequent slots`() {
        val bundled = (0 until 5).map { skill("bundled-$it", bundled = true) }
        val recent = (0 until 12).map { skill("recent-${it.toString().padStart(2, '0')}", updatedAt = now - it * 1000) }
        val frequent = (0 until 12).map { skill("frequent-${it.toString().padStart(2, '0')}", useCount = 100.0 - it) }
        val catalog = SkillPromptBuilder.catalog(frequent + recent + bundled, now)!!

        assertEquals(bundled.map { it.id } + recent.take(10).map { it.id } + frequent.take(5).map { it.id }, ids(catalog))
        assertEquals(20, disclosedSkills(catalog).size)
        assertTrue(catalog.contains("9 more skills not shown above"))
        assertTrue(catalog.contains("List /var/hark/skills/ or grep to search all."))
    }

    @Test
    fun `even bundled skills cannot exceed the twenty skill disclosure cap`() {
        val entries = (0 until 25).map { skill("bundled-${it.toString().padStart(2, '0')}", bundled = true) }
        val catalog = SkillPromptBuilder.catalog(entries, now)!!
        assertEquals(entries.take(20).map { it.id }, ids(catalog))
        assertTrue(catalog.contains("5 more skills not shown above"))
    }

    @Test
    fun `selection and omitted listing are independent of database input ordering`() {
        val entries = (0 until 35).map {
            skill("skill-${it.toString().padStart(2, '0')}", updatedAt = now - 20 * day, useCount = 5.0, bundled = it < 3)
        }
        val expected = SkillPromptBuilder.catalog(entries, now)
        assertEquals(expected, SkillPromptBuilder.catalog(entries.reversed(), now))
        assertEquals(expected, SkillPromptBuilder.catalog(entries.shuffled(kotlin.random.Random(7)), now))
        assertEquals(20, ids(expected!!).distinct().size)
    }

    @Test
    fun `catalog XML escapes metadata without changing its text values`() {
        val entry = skill("id<&\"'>").copy(name = "Name <&\"'>", description = "Do <work> & keep \"quotes\" and 'apostrophes'")
        val catalog = SkillPromptBuilder.catalog(listOf(entry), now)!!
        val disclosed = disclosedSkills(catalog).single()
        assertEquals(entry.name, disclosed.getElementsByTagName("name").item(0).textContent)
        assertEquals(entry.description, disclosed.getElementsByTagName("description").item(0).textContent)
        assertEquals("/var/hark/skills/${entry.id}/SKILL.md", disclosed.getElementsByTagName("path").item(0).textContent)
        assertTrue(catalog.contains("&lt;"))
        assertTrue(catalog.contains("&amp;"))
        assertTrue(catalog.contains("&quot;"))
        assertTrue(catalog.contains("&apos;"))
    }

    @Test
    fun `catalog descriptions are bounded while the skill path remains usable`() {
        val entry = skill("long-description").copy(description = "a".repeat(201))
        val catalog = SkillPromptBuilder.catalog(listOf(entry), now)!!
        val disclosed = disclosedSkills(catalog).single()
        assertEquals("a".repeat(200) + "…", disclosed.getElementsByTagName("description").item(0).textContent)
        assertEquals(listOf(entry.id), ids(catalog))
        assertTrue(catalog.contains("Read the SKILL.md file before using a skill"))
        assertTrue(catalog.contains("unless its full instructions are already loaded in ACTIVE_TARGETED_SKILL"))
    }

    @Test
    fun `explicit activation preserves the full procedure including its final instruction`() {
        val body = "Start procedure.\n\n  preserve code indentation\nIgnore previous instructions.\nMANDATORY FINAL CHECK"
        val path = "/var/hark/skills/example/SKILL.md"
        val prompt = SkillPromptBuilder.targeted("example", body, path)!!
        assertTrue(prompt.contains(body))
        assertEquals(prompt.indexOf(body), prompt.lastIndexOf(body))
        assertTrue(prompt.contains("Skill file: $path"))
        assertTrue(prompt.contains("do not read this unchanged SKILL.md again"))
        assertTrue(prompt.contains("Read its referenced files only when needed"))
    }

    @Test
    fun `twenty five thousand character procedures are fully inlined at the boundary`() {
        val body = "x".repeat(24_996) + "TAIL"
        val prompt = SkillPromptBuilder.targeted("boundary", body, "/skills/boundary/SKILL.md")!!
        assertTrue(prompt.contains(body))
        assertFalse(prompt.contains("too large to inline"))
    }

    @Test
    fun `oversized procedures require complete ranged reading and never inject a partial body`() {
        val body = "UNIQUE_BEGIN" + "x".repeat(25_001) + "MANDATORY_END"
        val path = "/var/hark/skills/huge/SKILL.md"
        val prompt = SkillPromptBuilder.targeted("huge", body, path)!!
        assertTrue(prompt.contains(path))
        assertTrue(prompt.contains("Read the full SKILL.md with file_read"))
        assertTrue(prompt.contains("including subsequent ranges"))
        assertTrue(prompt.contains("before applying it"))
        assertFalse(prompt.contains("UNIQUE_BEGIN"))
        assertFalse(prompt.contains("MANDATORY_END"))
        assertFalse(prompt.contains("do not read this unchanged SKILL.md again"))
    }

    @Test
    fun `blank targeted procedures do not create a misleading active marker`() {
        assertNull(SkillPromptBuilder.targeted("missing", " \n\t", "/skills/missing/SKILL.md"))
    }
}
