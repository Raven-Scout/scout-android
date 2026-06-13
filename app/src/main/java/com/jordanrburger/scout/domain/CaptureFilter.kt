package com.jordanrburger.scout.domain

/** Opt-in capture model (Jordan, 2026-06-12): nothing is captured unless its
 *  package is explicitly allowlisted. Replaces the original capture-everything
 *  + exclusions design, which flooded the inbox with system/personal noise. */
object CaptureFilter {
    fun shouldCapture(
        pkg: String,
        ownPkg: String,
        enabled: Boolean,
        allowed: Set<String>,
    ): Boolean = enabled && pkg != ownPkg && pkg in allowed
}
