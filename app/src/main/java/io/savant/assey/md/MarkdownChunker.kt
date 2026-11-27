package io.savant.assey.md

/**
 * 按空行分块，保证 `chunks.joinToString("") { it.text + it.joiner } == source`。
 * 这样翻译时不会因为分块而丢失/重复原文的空白与换行。
 */
class MarkdownChunker(private val maxChars: Int = 2000) {

    data class Chunk(val text: String, val joiner: String)

    fun chunk(source: String): List<Chunk> {
        if (source.isEmpty()) return emptyList()
        val blocks = splitBlocks(source)
        val chunks = ArrayList<Chunk>()
        val current = StringBuilder()
        var currentJoiner = ""

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(Chunk(current.toString(), currentJoiner))
                current.clear()
                currentJoiner = ""
            }
        }

        for ((block, separator) in blocks) {
            if (block.length > maxChars) {
                flush()
                val pieces = splitLongBlock(block, maxChars)
                pieces.forEachIndexed { index, piece ->
                    val joiner = if (index == pieces.lastIndex) separator else ""
                    chunks.add(Chunk(piece, joiner))
                }
                continue
            }
            if (current.isNotEmpty() && current.length + block.length > maxChars) {
                flush()
            }
            // 合并多个块时把块间分隔符写进文本，保证 text + joiner 拼接恒等
            if (current.isNotEmpty()) current.append(currentJoiner)
            current.append(block)
            currentJoiner = separator
        }
        flush()
        return chunks
    }

    /** 拆出 (块内容, 块后面的空行分隔符) 序列。 */
    private fun splitBlocks(source: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val n = source.length
        var i = 0
        while (i < n) {
            var j = i
            var sepStart = -1
            while (j < n) {
                val nl = source.indexOf('\n', j)
                if (nl < 0) {
                    j = n
                    break
                }
                var k = nl + 1
                while (k < n && (source[k] == ' ' || source[k] == '\t')) k++
                if (k < n && source[k] == '\n') {
                    sepStart = nl
                    var end = k + 1
                    while (end < n) {
                        var m = end
                        while (m < n && (source[m] == ' ' || source[m] == '\t')) m++
                        if (m < n && source[m] == '\n') end = m + 1 else break
                    }
                    j = end
                    break
                }
                j = nl + 1
            }
            if (sepStart < 0) {
                result.add(source.substring(i) to "")
                break
            }
            result.add(source.substring(i, sepStart) to source.substring(sepStart, j))
            i = j
        }
        return result
    }

    private fun splitLongBlock(block: String, max: Int): List<String> {
        if (block.length <= max) return listOf(block)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < block.length) {
            var end = minOf(start + max, block.length)
            if (end < block.length) {
                val nl = block.lastIndexOf('\n', end - 1)
                if (nl > start + max / 3) {
                    end = nl + 1
                } else {
                    var p = end
                    while (p > start + max / 3 && block[p - 1] !in SENTENCE_BOUNDARY) p--
                    if (p > start + max / 3) end = p
                    end = avoidPlaceholderCut(block, start, end)
                }
            }
            if (end <= start) end = minOf(start + max, block.length)
            pieces.add(block.substring(start, end))
            start = end
        }
        return pieces
    }

    private fun avoidPlaceholderCut(s: String, start: Int, end: Int): Int {
        if (end >= s.length) return end
        val open = s.lastIndexOf("[[", end - 1)
        if (open <= start) return end
        // 只有当占位符完整落在 [start, end) 内才允许在这里切
        val close = s.indexOf("]]", open + 2)
        return if (close in 0 until end - 1) end else open
    }

    companion object {
        private val SENTENCE_BOUNDARY =
            charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '，', ',', '、', ' ')
    }
}
