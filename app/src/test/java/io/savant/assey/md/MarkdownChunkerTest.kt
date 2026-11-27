package io.savant.assey.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {

    @Test
    fun `chunks reconstruct the source exactly`() {
        val source = (1..30).joinToString("\n\n") { index ->
            "## Section $index\n\n" + "This is a paragraph with some words. ".repeat(8)
        }
        val chunks = MarkdownChunker(300).chunk(source)
        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text + it.joiner })
    }

    @Test
    fun `long single paragraph is split without losing characters`() {
        val source = "word ".repeat(2000)
        val chunks = MarkdownChunker(500).chunk(source)
        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text + it.joiner })
    }

    @Test
    fun `placeholder is never cut in half`() {
        val source = "a".repeat(95) + "[[12]]" + "b".repeat(95)
        val chunks = MarkdownChunker(100).chunk(source)
        assertEquals(source, chunks.joinToString("") { it.text + it.joiner })
        chunks.forEach { chunk ->
            val opens = Regex("""\[\[""").findAll(chunk.text).count()
            val closes = Regex("""]]""").findAll(chunk.text).count()
            assertEquals("块内占位符必须成对：${chunk.text.take(20)}", opens, closes)
            assertFalse(chunk.text.endsWith("["))
            assertFalse(chunk.text.startsWith("]]"))
        }
    }

    @Test
    fun `blank line separators are preserved verbatim`() {
        val source = "A\n\n\nB\n\nC"
        val chunks = MarkdownChunker(1000).chunk(source)
        assertEquals(source, chunks.joinToString("") { it.text + it.joiner })
        assertEquals(1, chunks.size)
    }
}
