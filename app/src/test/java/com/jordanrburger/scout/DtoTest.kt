package com.jordanrburger.scout

import com.jordanrburger.scout.data.api.ActionItemDto
import com.jordanrburger.scout.data.api.NotificationEventDto
import com.jordanrburger.scout.data.api.ScoutJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoTest {

    private fun fixture(): List<ActionItemDto> {
        val text = javaClass.classLoader!!
            .getResource("fixtures/action-items-sample.json")!!.readText()
        return ScoutJson.decodeFromString(text)
    }

    @Test
    fun `fixture round-trips through ActionItemDto`() {
        val items = fixture()
        assertTrue(items.size >= 6)
        val first = items.first()
        assertEquals("QPD1", first.shortPrefix)
        assertEquals("🔴", first.priority)
        assertEquals("done", first.status)
        assertTrue(first.section.startsWith("🔴"))
    }

    @Test
    fun `short_prefix may be null and priority may be empty`() {
        val items = fixture()
        assertTrue(items.any { it.shortPrefix == null })
        assertTrue(items.any { it.priority.isEmpty() })
        assertNull(items.first { it.shortPrefix == null }.shortPrefix)
    }

    @Test
    fun `NotificationEventDto serializes with v=1 and snake_case`() {
        val e = NotificationEventDto(
            key = "k", pkg = "com.x", postTimeMs = 1L, title = "t", text = null,
            bigText = null, category = null, isOngoing = false, removed = false,
            capturedAt = "2026-06-12T15:00:00Z",
        )
        val s = ScoutJson.encodeToString(e)
        assertTrue(s.contains("\"v\":1"))
        assertTrue(s.contains("\"post_time_ms\":1"))
        assertTrue(s.contains("\"package\":\"com.x\""))
        assertTrue(s.contains("\"captured_at\":"))
    }
}
