package io.savant.assey.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MarkdownProtectorTest {

    private val protector = MarkdownProtector()

    @Test
    fun `roundtrip keeps code math urls images and table separators verbatim`() {
        val source = """
            # Paper Title

            Inline code `a[i] = b[i]` and math ${'$'}x^{2} + y_{1}${'$'} stay.

            ```python
            print("hello [[0]]")
            ```

            ![figure](images/fig1.jpg)

            See [the paper](https://example.com/paper.pdf) and https://example.com/raw.

            | Method | Score |
            |---|---|
            | Ours | 99.1 |
        """.trimIndent()

        val protected = protector.protect(source)
        assertFalse(protected.text.contains("images/fig1.jpg"))
        assertFalse(protected.text.contains("print("))
        assertFalse(protected.text.contains("https://example.com"))
        assertTrue(protected.text.contains("[[0]]"))

        val restored = protector.restore(protected.text, protected.tokens)
        assertEquals(source, restored)
    }

    @Test
    fun `table separator line is protected as a whole line`() {
        val source = "| A | B |\n| :--- | ---: |\n| 1 | 2 |"
        val protected = protector.protect(source)
        val separatorToken = protected.tokens.firstOrNull { it.contains("---") }
        assertTrue("分隔行应被保护", separatorToken != null)
        assertEquals(source, protector.restore(protected.text, protected.tokens))
    }

    @Test
    fun `currency is not treated as inline math`() {
        val protected = protector.protect("It costs ${'$'}5 and ${'$'}10 today.")
        assertEquals(0, protected.tokens.size)
    }

    @Test
    fun `inline math is protected`() {
        val protected = protector.protect("Let ${'$'}O(n\\log n)${'$'} be the bound.")
        assertEquals(1, protected.tokens.size)
        assertEquals("${'$'}O(n\\log n)${'$'}", protected.tokens[0])
    }

    @Test
    fun `missing placeholder throws`() {
        val protected = protector.protect("Use `code` here.")
        try {
            protector.restore("Use here.", protected.tokens)
            fail("应当检测到占位符缺失")
        } catch (e: MarkdownProtector.PlaceholderException) {
            assertTrue(e.missing.isNotEmpty())
        }
    }

    @Test
    fun `duplicated placeholder throws`() {
        val protected = protector.protect("Use `code` here.")
        try {
            protector.restore("[[0]] and [[0]]", protected.tokens)
            fail("应当检测到占位符重复")
        } catch (e: MarkdownProtector.PlaceholderException) {
            assertTrue(e.duplicated.isNotEmpty())
        }
    }

    @Test
    fun `unknown placeholder throws`() {
        val protected = protector.protect("Use `code` here.")
        try {
            protector.restore("[[0]] [[9]]", protected.tokens)
            fail("应当检测到未知占位符")
        } catch (e: MarkdownProtector.PlaceholderException) {
            assertTrue(e.unknown.isNotEmpty())
        }
    }

    @Test
    fun `unclosed fence is protected to end of document`() {
        val source = "before\n```kotlin\nval x = 1\n"
        val protected = protector.protect(source)
        assertEquals(1, protected.tokens.size)
        assertEquals(source, protector.restore(protected.text, protected.tokens))
    }

    @Test
    fun `html tags are protected`() {
        val protected = protector.protect("A <br/> tag and <span class=\"x\">text</span>.")
        assertEquals(3, protected.tokens.size)
    }
}
