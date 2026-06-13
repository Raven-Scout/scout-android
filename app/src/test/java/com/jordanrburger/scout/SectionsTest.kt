package com.jordanrburger.scout

import com.jordanrburger.scout.data.api.ActionItemDto
import com.jordanrburger.scout.domain.Priority
import com.jordanrburger.scout.domain.SectionKind
import com.jordanrburger.scout.domain.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {

    private fun dto(
        section: String,
        prefix: String? = "X",
        status: String = "open",
        priority: String = "",
        title: String = "t",
    ) = ActionItemDto(title = title, priority = priority, status = status, section = section, shortPrefix = prefix)

    @Test
    fun `fixed order with daily suffixes tolerated`() {
        val groups = Sections.organize(
            listOf(
                dto("🟢 Watching (no action yet)"),
                dto("💡 Today's Focus (you're back at a desk — Fri Jun 12)"),
                dto("🟡 To Do (work — back at a desk this weekend / today)"),
                dto("🔴 Urgent / Time-Sensitive"),
                dto("✅ Recently Completed", status = "done"),
            )
        )
        assertEquals(
            listOf(SectionKind.FOCUS, SectionKind.URGENT, SectionKind.TODO, SectionKind.WATCHING, SectionKind.DONE),
            groups.map { it.kind },
        )
    }

    @Test
    fun `done section collapsed by default and others not`() {
        assertTrue(SectionKind.DONE.collapsedByDefault)
        assertTrue(SectionKind.entries.filter { it != SectionKind.DONE }.none { it.collapsedByDefault })
    }

    @Test
    fun `meetings run-notes and unknown sections dropped`() {
        val groups = Sections.organize(
            listOf(
                dto("📅 Today's Meetings (Fri Jun 12)"),
                dto("🪵 Run notes & connector availability"),
                dto("Some Future Section"),
                dto("🔴 Urgent / Time-Sensitive"),
            )
        )
        assertEquals(listOf(SectionKind.URGENT), groups.map { it.kind })
        assertEquals(1, groups[0].items.size)
    }

    @Test
    fun `personal merges into todo tail`() {
        val groups = Sections.organize(
            listOf(
                dto("Personal", title = "buy gift"),
                dto("🟡 To Do (work)", title = "work thing"),
            )
        )
        assertEquals(listOf(SectionKind.TODO), groups.map { it.kind })
        assertEquals(listOf("work thing", "buy gift"), groups[0].items.map { it.title })
    }

    @Test
    fun `empty groups omitted`() {
        val groups = Sections.organize(listOf(dto("🟢 Watching")))
        assertEquals(listOf(SectionKind.WATCHING), groups.map { it.kind })
    }

    @Test
    fun `priority emoji and done status map to domain`() {
        val groups = Sections.organize(
            listOf(
                dto("🔴 Urgent / Time-Sensitive", priority = "🔴", status = "open"),
                dto("✅ Recently Completed", priority = "🟡", status = "done"),
            )
        )
        val urgent = groups.first { it.kind == SectionKind.URGENT }.items[0]
        assertEquals(Priority.URGENT, urgent.priority)
        assertTrue(!urgent.done)
        val done = groups.first { it.kind == SectionKind.DONE }.items[0]
        assertEquals(Priority.TODO, done.priority)
        assertTrue(done.done)
    }
}
