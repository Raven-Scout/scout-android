package com.jordanrburger.scout

import com.jordanrburger.scout.domain.CaptureFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFilterTest {

    @Test
    fun `empty allowlist captures nothing — opt-in model`() {
        assertFalse(CaptureFilter.shouldCapture("com.whatsapp", "com.jordanrburger.scout", true, emptySet()))
        assertFalse(CaptureFilter.shouldCapture("android", "com.jordanrburger.scout", true, emptySet()))
    }

    @Test
    fun `only allowlisted packages capture`() {
        val allowed = setOf("com.slack", "com.google.android.gm")
        assertTrue(CaptureFilter.shouldCapture("com.slack", "own", true, allowed))
        assertFalse(CaptureFilter.shouldCapture("com.whatsapp", "own", true, allowed))
    }

    @Test
    fun `own package never captures even if allowlisted`() {
        assertFalse(CaptureFilter.shouldCapture("own", "own", true, setOf("own")))
    }

    @Test
    fun `master toggle off captures nothing`() {
        assertFalse(CaptureFilter.shouldCapture("com.slack", "own", false, setOf("com.slack")))
    }
}
