package com.jordanrburger.scout.domain

import com.jordanrburger.scout.data.api.BridgeConfig
import java.net.URLDecoder

/** Parses the bridge's pairing QR payload: scout://pair?u=<url>&s=<secret>.
 *  Pure JVM (no android.net.Uri) so it's unit-testable; query parsing is
 *  manual because java.net.URI rejects some encoded payloads in opaque URIs. */
object PairingUri {

    fun parse(text: String): BridgeConfig? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("scout://pair?")) return null
        val params = trimmed.substringAfter('?')
            .split('&')
            .mapNotNull { param ->
                val idx = param.indexOf('=')
                if (idx <= 0) null
                else param.take(idx) to URLDecoder.decode(param.substring(idx + 1), "UTF-8")
            }
            .toMap()
        val url = params["u"]?.trim()?.trimEnd('/') ?: return null
        val secret = params["s"]?.trim() ?: return null
        if (url.isBlank() || secret.isBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return BridgeConfig(url, secret)
    }
}
