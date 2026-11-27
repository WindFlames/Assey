package io.savant.assey.md

import io.savant.assey.core.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 与 HTTP 解耦的翻译引擎：只依赖一个 [ChatClient]。
 * 负责保护片段 → 分块 → 重试/拆分 → 校验 → 还原。
 */
class MarkdownTranslationEngine(
    private val chat: ChatClient,
    private val protector: MarkdownProtector = MarkdownProtector(),
    private val chunker: MarkdownChunker = MarkdownChunker(2000),
    private val maxAttempts: Int = 3
) {

    fun interface ChatClient {
        suspend fun complete(prompt: String): String
    }

    suspend fun translate(
        markdown: String,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        if (markdown.isBlank()) return markdown
        val protected = protector.protect(markdown)
        val chunks = chunker.chunk(protected.text)
        if (chunks.isEmpty()) return markdown

        val out = StringBuilder(markdown.length + 1024)
        onProgress(0, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val translated = translateChunk(chunk.text, target, depth = 0)
            out.append(translated.trim())
            out.append(chunk.joiner)
            onProgress(index + 1, chunks.size)
        }
        return protector.restore(out.toString(), protected.tokens)
    }

    private suspend fun translateChunk(text: String, target: String, depth: Int): String {
        if (text.isBlank()) return text
        val placeholders = protector.placeholderIndices(text)
        var lastProblem = "未知错误"

        repeat(maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val raw = chat.complete(buildPrompt(text, target, attempt > 0))
                val cleaned = clean(raw)
                if (cleaned.isBlank()) {
                    lastProblem = "模型返回空内容"
                } else {
                    val problem = validate(text, cleaned, placeholders)
                    if (problem == null) return cleaned
                    lastProblem = problem
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Cancelled) {
                throw e
            } catch (e: AppError.AuthError) {
                throw e
            } catch (e: AppError.InvalidInput) {
                throw e
            } catch (e: Throwable) {
                lastProblem = e.message ?: e.javaClass.simpleName
            }
            if (attempt < maxAttempts - 1) {
                delay(500L * (attempt + 1))
            }
        }

        // 仍失败：把块对半拆开再翻（最多递归两层）
        if (depth < 2 && text.length > 160) {
            val split = splitPoint(text)
            if (split in 1 until text.length) {
                val left = translateChunk(text.substring(0, split), target, depth + 1)
                val right = translateChunk(text.substring(split), target, depth + 1)
                return left + right
            }
        }

        throw AppError.TranslationError(
            "翻译失败：模型输出不完整（$lastProblem）。已中止，未写出损坏文件。"
        )
    }

    private fun buildPrompt(text: String, target: String, retry: Boolean): String {
        val language = when (target.lowercase()) {
            "zh", "zh-cn", "chinese", "中文", "简体中文" -> "中文"
            "en", "english", "英文" -> "英文"
            else -> target
        }
        return buildString {
            append("请将下面的 Markdown 片段翻译为")
            append(language)
            append("，保持 Markdown 结构不变（标题层级、列表符号、引用、表格、链接语法都不要改动），")
            append("只输出译文，不要解释，不要添加代码围栏。\n")
            append("所有形如 [[0]] 的占位符必须原样保留、位置不变，禁止翻译、增删、改写或展开占位符内容。\n")
            if (retry) {
                append("注意：上一次输出丢失或改动了占位符，这次必须完整保留每一个占位符。\n")
            }
            append("\n")
            append(text)
        }
    }

    private fun clean(raw: String): String {
        var text = raw.trim()
        val fenced = FENCED_WRAPPER.find(text)
        if (fenced != null) {
            text = fenced.groupValues[1].trim()
        }
        return text
    }

    private fun validate(input: String, output: String, placeholders: Set<Int>): String? {
        val counts = HashMap<Int, Int>()
        MarkdownProtector.PLACEHOLDER.findAll(output).forEach { m ->
            val index = m.groupValues[1].toIntOrNull() ?: return@forEach
            counts[index] = (counts[index] ?: 0) + 1
        }
        val missing = placeholders.filter { (counts[it] ?: 0) == 0 }
        if (missing.isNotEmpty()) return "丢失占位符 $missing"
        val duplicated = placeholders.filter { (counts[it] ?: 0) > 1 }
        if (duplicated.isNotEmpty()) return "占位符重复 $duplicated"
        val unknown = counts.keys - placeholders
        if (unknown.isNotEmpty()) return "出现未知占位符 $unknown"

        if (headingLevels(input) != headingLevels(output)) return "标题结构发生变化"
        if (input.count { it == '`' } != output.count { it == '`' }) return "代码标记发生变化"
        if (tableSeparatorCount(input) != tableSeparatorCount(output)) return "表格分隔行发生变化"
        return null
    }

    private fun headingLevels(text: String): List<Int> =
        text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("#")) return@mapNotNull null
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && trimmed.getOrNull(level)?.isWhitespace() == true) level else null
            }
            .toList()

    private fun tableSeparatorCount(text: String): Int =
        text.lineSequence().count { TABLE_SEPARATOR.matches(it) }

    private fun splitPoint(text: String): Int {
        val mid = text.length / 2
        for (marker in SPLIT_MARKERS) {
            val index = text.indexOf(marker, mid)
            if (index in 1 until text.length - 1) return index + 1
        }
        val space = text.indexOf(' ', mid)
        if (space in 1 until text.length - 1) return space + 1

        var p = mid
        val open = text.lastIndexOf("[[", mid)
        val close = text.lastIndexOf("]]", mid)
        if (open > close) p = open
        return if (p in 1 until text.length) p else -1
    }

    companion object {
        private val FENCED_WRAPPER = Regex("^```[a-zA-Z0-9_-]*\\s*\\n([\\s\\S]*?)\\n?```$")
        private val TABLE_SEPARATOR =
            Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")
        private val SPLIT_MARKERS = charArrayOf('\n', '。', '！', '？', '.', ';', '；')
    }
}
