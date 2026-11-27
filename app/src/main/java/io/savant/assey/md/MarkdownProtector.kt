package io.savant.assey.md

/**
 * 翻译前把「不可翻译片段」替换成 [[n]] 占位符，翻译后还原并校验。
 *
 * 保护范围：
 *  - 围栏代码块 ``` / ~~~
 *  - 行内代码 `code`
 *  - 块级公式 $$...$$ 与行内公式 $...$
 *  - Markdown 图片 / 链接整体（保证图片路径与 URL 逐字不变）
 *  - 裸 URL、HTML 标签、引用式链接定义行
 *  - 表格分隔行（保证 `|---|` 逐字不变）
 *
 * 纯 Kotlin 实现，无 Android 依赖，可被 JVM 单元测试覆盖。
 */
class MarkdownProtector {

    data class Protected(val text: String, val tokens: List<String>)

    class PlaceholderException(
        val missing: List<Int>,
        val duplicated: List<Int>,
        val unknown: List<Int>
    ) : Exception(
        "占位符不完整：missing=$missing duplicated=$duplicated unknown=$unknown"
    )

    fun protect(source: String): Protected {
        val tokens = ArrayList<String>()
        val out = StringBuilder(source.length + 64)
        var i = 0
        while (i < source.length) {
            val length = matchAt(source, i, out, tokens)
            if (length > 0) {
                i += length
            } else {
                out.append(source[i])
                i++
            }
        }
        return Protected(out.toString(), tokens)
    }

    /** 校验并还原；占位符缺失/重复/未知时抛 [PlaceholderException]。 */
    fun restore(text: String, tokens: List<String>): String {
        val counts = IntArray(tokens.size)
        val unknown = ArrayList<Int>()
        PLACEHOLDER.findAll(text).forEach { m ->
            val index = m.groupValues[1].toIntOrNull()
            if (index == null || index !in tokens.indices) {
                unknown.add(index ?: -1)
            } else {
                counts[index]++
            }
        }
        val missing = counts.indices.filter { counts[it] == 0 }
        val duplicated = counts.indices.filter { counts[it] > 1 }
        if (missing.isNotEmpty() || duplicated.isNotEmpty() || unknown.isNotEmpty()) {
            throw PlaceholderException(missing, duplicated, unknown)
        }
        return PLACEHOLDER.replace(text) { m -> tokens[m.groupValues[1].toInt()] }
    }

    fun placeholderIndices(text: String): Set<Int> =
        PLACEHOLDER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    private fun matchAt(
        src: String,
        i: Int,
        out: StringBuilder,
        tokens: MutableList<String>
    ): Int {
        val atLineStart = i == 0 || src[i - 1] == '\n'

        if (atLineStart) {
            fenceAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
            tableSeparatorAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
            referenceDefinitionAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
        }

        // 块级公式
        if (src.startsWith("$$", i)) {
            val end = src.indexOf("$$", i + 2)
            if (end > i) return emit(out, tokens, src.substring(i, end + 2))
        }

        // 行内代码
        if (src[i] == '`') {
            var n = 0
            while (i + n < src.length && src[i + n] == '`') n++
            val marker = "`".repeat(n)
            val end = src.indexOf(marker, i + n)
            if (end > i) return emit(out, tokens, src.substring(i, end + n))
        }

        // 图片 / 链接
        if (src.startsWith("![", i) || src[i] == '[') {
            linkAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
        }

        // HTML 注释 / 标签
        if (src[i] == '<') {
            htmlAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
        }

        // 裸 URL
        if (src.startsWith("http://", i) || src.startsWith("https://", i)) {
            var p = i
            while (p < src.length && src[p] !in URL_STOP_CHARS) p++
            if (p > i + 7) return emit(out, tokens, src.substring(i, p))
        }

        // 行内公式
        if (src[i] == '$' && !src.startsWith("$$", i)) {
            inlineMathAt(src, i)?.let { return emit(out, tokens, src.substring(i, i + it)) }
        }

        return 0
    }

    private fun emit(out: StringBuilder, tokens: MutableList<String>, original: String): Int {
        val index = tokens.size
        tokens.add(original)
        out.append("[[").append(index).append("]]")
        return original.length
    }

    private fun fenceAt(src: String, i: Int): Int? {
        var p = i
        var spaces = 0
        while (p < src.length && src[p] == ' ' && spaces < 4) {
            p++
            spaces++
        }
        if (spaces > 3 || p >= src.length) return null
        val c = src[p]
        if (c != '`' && c != '~') return null
        var n = 0
        while (p + n < src.length && src[p + n] == c) n++
        if (n < 3) return null

        var lineEnd = src.indexOf('\n', p)
        if (lineEnd < 0) return src.length - i

        var pos = lineEnd + 1
        while (pos < src.length) {
            val end = src.indexOf('\n', pos).let { if (it < 0) src.length else it }
            val line = src.substring(pos, end)
            val trimmed = line.trimStart()
            if (trimmed.isNotEmpty() && trimmed[0] == c) {
                val run = trimmed.takeWhile { it == c }.length
                if (run >= n && trimmed.drop(run).isBlank()) {
                    return end - i
                }
            }
            pos = end + 1
        }
        // 未闭合围栏：保护到文末，避免模型改动其中的代码
        return src.length - i
    }

    private fun tableSeparatorAt(src: String, i: Int): Int? {
        val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val line = src.substring(i, end)
        if (line.length > 500) return null
        return if (TABLE_SEPARATOR.matches(line)) end - i else null
    }

    private fun referenceDefinitionAt(src: String, i: Int): Int? {
        val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val line = src.substring(i, end)
        return if (REFERENCE_DEF.matches(line)) end - i else null
    }

    private fun linkAt(src: String, i: Int): Int? {
        val bracketStart = if (src.startsWith("![", i)) i + 1 else i
        val lineEnd = src.indexOf('\n', bracketStart)
        val limit = if (lineEnd < 0) src.length else lineEnd
        val closeBracket = src.indexOf(']', bracketStart + 1)
        if (closeBracket < 0 || closeBracket > limit) return null
        if (closeBracket + 1 >= src.length || src[closeBracket + 1] != '(') return null

        var depth = 0
        var p = closeBracket + 1
        while (p < src.length) {
            when (src[p]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return p - i + 1
                }
                '\n' -> return null
            }
            p++
        }
        return null
    }

    private fun htmlAt(src: String, i: Int): Int? {
        if (src.startsWith("<!--", i)) {
            val end = src.indexOf("-->", i + 4)
            if (end > 0) return end + 3 - i
            return null
        }
        val next = src.getOrNull(i + 1) ?: return null
        if (!(next.isLetter() || next == '/' || next == '!')) return null
        val end = src.indexOf('>', i + 1)
        if (end < 0) return null
        val body = src.substring(i + 1, end)
        if (body.contains('<')) return null
        if (body.length > 2000) return null
        return end + 1 - i
    }

    private fun inlineMathAt(src: String, i: Int): Int? {
        val end = src.indexOf('$', i + 1)
        if (end <= i + 1) return null
        if (src.substring(i + 1, end).contains('\n')) return null
        val content = src.substring(i + 1, end)
        if (content.length > 400) return null
        if (content.first().isWhitespace() || content.last().isWhitespace()) return null
        if (content.none { it.isLetter() || it == '\\' || it == '^' || it == '_' || it == '{' }) return null
        // 避免把 "$5" 这类金额当公式
        if (content.all { it.isDigit() || it == '.' || it == ',' }) return null
        return end + 1 - i
    }

    companion object {
        val PLACEHOLDER = Regex("""\[\[(\d{1,4})]]""")

        private val TABLE_SEPARATOR =
            Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")

        private val REFERENCE_DEF =
            Regex("""^\s{0,3}\[[^\]]+]:\s*\S+.*$""")

        private val URL_STOP_CHARS = charArrayOf(
            ' ', '\t', '\n', '\r', ')', ']', '}', '>', '"', '\'', '，', '。', '、'
        )
    }
}
