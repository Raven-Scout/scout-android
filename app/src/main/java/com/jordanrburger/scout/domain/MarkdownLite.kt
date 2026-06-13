package com.jordanrburger.scout.domain

enum class Style { PLAIN, BOLD, ITALIC, CODE }

data class Segment(val text: String, val style: Style)

/**
 * Tiny single-pass renderer for vault action-item titles (spec §5 markdown-lite).
 * Handles: [[wikilinks]] (alias or basename), [text](url), **bold**, _italic_,
 * `code`, ~~strike~~ (kept as plain text). Unmatched markers stay literal.
 * Styled runs are flat: markers inside a styled run reduce to their text.
 */
object MarkdownLite {

    fun parse(input: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                segments.add(Segment(plain.toString(), Style.PLAIN))
                plain.clear()
            }
        }

        var i = 0
        while (i < input.length) {
            when {
                input.startsWith("[[", i) -> {
                    val end = input.indexOf("]]", i + 2)
                    if (end >= 0) {
                        val inner = input.substring(i + 2, end)
                        plain.append(
                            if ('|' in inner) inner.substringAfter('|')
                            else inner.substringAfterLast('/')
                        )
                        i = end + 2
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                input.startsWith("**", i) -> {
                    val end = input.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        segments.add(Segment(flatten(input.substring(i + 2, end)), Style.BOLD))
                        i = end + 2
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                input.startsWith("~~", i) -> {
                    val end = input.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        plain.append(flatten(input.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                input[i] == '`' -> {
                    val end = input.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        segments.add(Segment(input.substring(i + 1, end), Style.CODE))
                        i = end + 1
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                input[i] == '[' -> {
                    val close = input.indexOf(']', i + 1)
                    val urlEnd = if (close > i && close + 1 < input.length && input[close + 1] == '(') {
                        input.indexOf(')', close + 2)
                    } else -1
                    if (urlEnd > 0) {
                        plain.append(flatten(input.substring(i + 1, close)))
                        i = urlEnd + 1
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                input[i] == '_' && (i == 0 || input[i - 1].isWhitespace()) -> {
                    val end = findItalicClose(input, i + 1)
                    if (end > i + 1) {
                        flush()
                        segments.add(Segment(flatten(input.substring(i + 1, end)), Style.ITALIC))
                        i = end + 1
                    } else {
                        plain.append(input[i]); i++
                    }
                }
                else -> {
                    plain.append(input[i]); i++
                }
            }
        }
        flush()
        return segments
    }

    /** Inner content of a styled run, with nested markers reduced to text. */
    private fun flatten(s: String) = parse(s).joinToString("") { it.text }

    /** Closing `_` must end the string or precede a non-alphanumeric (avoids snake_case). */
    private fun findItalicClose(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '_' && (j + 1 == s.length || !s[j + 1].isLetterOrDigit())) return j
            j++
        }
        return -1
    }
}
