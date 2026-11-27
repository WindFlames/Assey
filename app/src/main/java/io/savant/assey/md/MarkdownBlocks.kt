package io.savant.assey.md

/**
 * 轻量 Markdown 分块解析（只服务于预览渲染）。
 * 纯 Kotlin，可单元测试；不支持全部 CommonMark，但覆盖论文常见结构：
 * 标题 / 段落 / 列表 / 引用 / 表格 / 代码块 / 公式 / 图片。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullets(val items: List<MdItem>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Image(val alt: String, val path: String) : MdBlock
    data class Math(val text: String) : MdBlock
    data object Divider : MdBlock
}

data class MdItem(val level: Int, val marker: String, val text: String)

object MarkdownBlocks {

    fun parse(markdown: String): List<MdBlock> {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 围栏代码块
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.take(3)
                val language = trimmed.removePrefix(fence).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++
                blocks.add(MdBlock.Code(language, code.toString().trimEnd('\n')))
                continue
            }

            // 块级公式
            if (trimmed.startsWith("$$")) {
                val first = trimmed.removePrefix("$$")
                if (first.contains("$$")) {
                    blocks.add(MdBlock.Math(first.substringBefore("$$").trim()))
                    i++
                    continue
                }
                val body = StringBuilder()
                if (first.isNotBlank()) body.append(first).append('\n')
                i++
                while (i < lines.size && !lines[i].contains("$$")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) {
                    body.append(lines[i].substringBefore("$$"))
                    i++
                }
                blocks.add(MdBlock.Math(body.toString().trim()))
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                blocks.add(
                    MdBlock.Heading(
                        heading.groupValues[1].length,
                        heading.groupValues[2].trim()
                    )
                )
                i++
                continue
            }

            if (HR.matches(trimmed)) {
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            if (isTableStart(lines, i)) {
                val header = splitRow(lines[i])
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val row = splitRow(lines[i])
                    if (row.isNotEmpty()) rows.add(row)
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }

            if (trimmed.startsWith(">")) {
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trimStart()).append('\n')
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString().trimEnd('\n')))
                continue
            }

            if (LIST_ITEM.matches(line)) {
                val items = ArrayList<MdItem>()
                while (i < lines.size) {
                    val current = LIST_ITEM.matchEntire(lines[i]) ?: break
                    val indent = current.groupValues[1].replace("\t", "    ").length
                    items.add(
                        MdItem(
                            level = (indent / 2).coerceAtMost(4),
                            marker = current.groupValues[2],
                            text = current.groupValues[3]
                        )
                    )
                    i++
                }
                if (items.isNotEmpty()) blocks.add(MdBlock.Bullets(items))
                continue
            }

            val image = IMAGE_ONLY.matchEntire(trimmed)
            if (image != null) {
                blocks.add(MdBlock.Image(image.groupValues[1], image.groupValues[2].trim()))
                i++
                continue
            }

            // 普通段落：合并到空行或下一个块开始
            val paragraph = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(lines[i].trim())
                i++
            }
            if (paragraph.isEmpty()) {
                i++
            } else {
                blocks.add(MdBlock.Paragraph(paragraph.toString()))
            }
        }
        return blocks
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return true
        if (trimmed.startsWith("$$")) return true
        if (trimmed.startsWith(">")) return true
        if (HEADING.matches(trimmed)) return true
        if (HR.matches(trimmed)) return true
        if (LIST_ITEM.matches(line)) return true
        if (IMAGE_ONLY.matches(trimmed)) return true
        if (isTableStart(lines, index)) return true
        return false
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val separator = lines[index + 1]
        if (!header.contains('|')) return false
        if (!TABLE_SEPARATOR.matches(separator.trim())) return false
        return splitRow(header).isNotEmpty()
    }

    fun splitRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        return text.replace("\\|", "\u0001")
            .split('|')
            .map { it.trim().replace('\u0001', '|') }
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val HR = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
    private val LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val IMAGE_ONLY = Regex("""^!\[([^]]*)]\(([^)]+)\)\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$""")
}
