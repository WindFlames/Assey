package io.savant.assey.md

import io.savant.assey.core.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MarkdownTranslationEngineTest {

    private fun engine(
        attempts: Int = 2,
        client: suspend (String) -> String
    ) = MarkdownTranslationEngine(
        chat = MarkdownTranslationEngine.ChatClient { prompt -> client(prompt) },
        maxAttempts = attempts
    )

    @Test
    fun `identity translation roundtrips protected fragments`() = runBlocking {
        val source = """
            # Title

            Use `code` and ${'$'}x^2${'$'} and https://example.com/a.

            ![fig](images/a.png)

            | A | B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()

        val output = engine { prompt -> prompt.substringAfter("\n\n") }.translate(source, "中文") { _, _ -> }

        assertEquals(source, output)
    }

    @Test
    fun `translation keeps placeholders and restores originals`() = runBlocking {
        val source = "# Title\n\nRun `make test` and see https://example.com."
        val output = engine { prompt ->
            prompt.substringAfter("\n\n")
                .replace("Title", "标题")
                .replace("Run", "运行")
                .replace("and see", "并查看")
        }.translate(source, "中文") { _, _ -> }

        assertTrue(output.contains("标题"))
        assertTrue(output.contains("`make test`"))
        assertTrue(output.contains("https://example.com"))
    }

    @Test
    fun `model output wrapped in code fence is cleaned`() = runBlocking {
        val source = "Hello world."
        val output = engine { prompt ->
            "```markdown\n" + prompt.substringAfter("\n\n") + "\n```"
        }.translate(source, "中文") { _, _ -> }
        assertEquals("Hello world.", output)
    }

    @Test
    fun `dropped placeholder fails instead of writing broken output`() = runBlocking {
        val source = "Use `code` here."
        try {
            engine(attempts = 2) { "Use here." }.translate(source, "中文") { _, _ -> }
            fail("占位符丢失时必须报错")
        } catch (e: AppError.TranslationError) {
            assertTrue(e.message.orEmpty().contains("翻译失败"))
        }
    }

    @Test
    fun `retry succeeds when second attempt is valid`() = runBlocking {
        var calls = 0
        val source = "Use `code` here."
        val output = engine(attempts = 3) { prompt ->
            calls++
            if (calls == 1) "Use here." else prompt.substringAfter("\n\n")
        }.translate(source, "中文") { _, _ -> }

        assertEquals(2, calls)
        assertEquals(source, output)
    }

    @Test
    fun `progress reports every chunk`() = runBlocking {
        val source = (1..5).joinToString("\n\n") { "Paragraph $it " + "x".repeat(400) }
        val progress = ArrayList<Pair<Int, Int>>()
        engine { prompt -> prompt.substringAfter("\n\n") }
            .translate(source, "中文") { done, total -> progress.add(done to total) }
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.last().first, progress.last().second)
    }
}
