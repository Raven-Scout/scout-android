package com.jordanrburger.scout

import com.jordanrburger.scout.domain.PairingUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUriTest {

    @Test
    fun `parses url and secret from pairing uri`() {
        val config = PairingUri.parse("scout://pair?u=http%3A%2F%2F10.0.0.199%3A8787&s=abc123")!!
        assertEquals("http://10.0.0.199:8787", config.baseUrl)
        assertEquals("abc123", config.secret)
    }

    @Test
    fun `decodes reserved characters in secret`() {
        val config = PairingUri.parse("scout://pair?u=http%3A%2F%2Fh%3A1&s=a%26b%3Dc")!!
        assertEquals("a&b=c", config.secret)
    }

    @Test
    fun `trailing slash on url is trimmed`() {
        val config = PairingUri.parse("scout://pair?u=http%3A%2F%2Fh%3A1%2F&s=x")!!
        assertEquals("http://h:1", config.baseUrl)
    }

    @Test
    fun `rejects wrong scheme or host`() {
        assertNull(PairingUri.parse("https://pair?u=http%3A%2F%2Fh&s=x"))
        assertNull(PairingUri.parse("scout://action-items/QPD1"))
    }

    @Test
    fun `rejects missing or blank params`() {
        assertNull(PairingUri.parse("scout://pair?u=http%3A%2F%2Fh%3A1"))
        assertNull(PairingUri.parse("scout://pair?s=x"))
        assertNull(PairingUri.parse("scout://pair?u=&s=x"))
    }

    @Test
    fun `rejects non-http url payloads`() {
        assertNull(PairingUri.parse("scout://pair?u=ftp%3A%2F%2Fh&s=x"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(PairingUri.parse("not a uri at all"))
        assertNull(PairingUri.parse(""))
    }
}
