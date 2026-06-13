package com.jordanrburger.scout

import com.jordanrburger.scout.domain.MarkdownLite
import com.jordanrburger.scout.domain.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLiteTest {

    private fun flat(s: String) = MarkdownLite.parse(s).joinToString("") { it.text }

    @Test
    fun `wikilink with alias renders label`() =
        assertEquals("Alex", flat("[[people/alex-kim|Alex]]"))

    @Test
    fun `bare wikilink renders basename`() =
        assertEquals("data-pipeline", flat("[[projects/data-pipeline]]"))

    @Test
    fun `bold becomes BOLD segment`() {
        val seg = MarkdownLite.parse("a **b** c")
        assertEquals(listOf(Style.PLAIN, Style.BOLD, Style.PLAIN), seg.map { it.style })
        assertEquals("b", seg[1].text)
    }

    @Test
    fun `backtick code segment`() =
        assertEquals(Style.CODE, MarkdownLite.parse("`weekly:friday`")[0].style)

    @Test
    fun `md link keeps text drops url`() =
        assertEquals("Linear project", flat("[Linear project](https://linear.app/x)"))

    @Test
    fun `unmatched asterisks stay literal`() =
        assertEquals("a ** b", flat("a ** b"))

    @Test
    fun `italic underscore`() {
        val seg = MarkdownLite.parse("_(carries 5/22)_")
        assertEquals(Style.ITALIC, seg[0].style)
        assertEquals("(carries 5/22)", seg[0].text)
    }

    @Test
    fun `strikethrough kept plain without markers`() =
        assertEquals("TASK-42 Acme", flat("~~TASK-42 Acme~~"))

    @Test
    fun `mixed vault title strips wikilinks and styles bold`() {
        val s = "**Post the [[data-pipeline]] update** _(due today)_ — see `scoutctl`"
        assertEquals("Post the data-pipeline update (due today) — see scoutctl", flat(s))
    }

    @Test
    fun `empty string parses to empty list`() =
        assertEquals(0, MarkdownLite.parse("").size)
}
