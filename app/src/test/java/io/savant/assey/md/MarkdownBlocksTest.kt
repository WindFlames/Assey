package io.savant.assey.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `parses headings paragraphs and code`() {
        val blocks = MarkdownBlocks.parse("# H1\n\nText here.\n\n```kotlin\nval a = 1\n```")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals("val a = 1", (blocks[2] as MdBlock.Code).code)
    }

    @Test
    fun `parses gfm table`() {
        val md = "| Method | Score |\n| --- | ---: |\n| Ours | 99.1 |\n| Base | 90 |"
        val table = MarkdownBlocks.parse(md).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Method", "Score"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Ours", "99.1"), table.rows[0])
    }

    @Test
    fun `parses image block and list items`() {
        val md = "![fig 1](images/fig1.jpg)\n\n- one\n- two\n  - nested"
        val blocks = MarkdownBlocks.parse(md)
        val image = blocks.filterIsInstance<MdBlock.Image>().single()
        assertEquals("images/fig1.jpg", image.path)
        val list = blocks.filterIsInstance<MdBlock.Bullets>().single()
        assertEquals(3, list.items.size)
        assertEquals(1, list.items[2].level)
    }

    @Test
    fun `parses display math`() {
        val blocks = MarkdownBlocks.parse("$$\nE = mc^2\n$$")
        assertEquals("E = mc^2", (blocks.single() as MdBlock.Math).text)
    }
}
