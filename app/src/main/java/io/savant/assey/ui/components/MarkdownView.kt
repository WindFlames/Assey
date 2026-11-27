package io.savant.assey.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.savant.assey.md.MarkdownBlocks
import io.savant.assey.md.MdBlock
import io.savant.assey.md.MdItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/**
 * 自绘 Markdown 预览：标题 / 列表 / 引用 / 代码 / 表格 / 公式 / 本地图片。
 * 图片相对路径以 [assetsDir] 为根，保证解压后的 images/xxx 可直接显示。
 */
@Composable
fun MarkdownView(markdown: String, assetsDir: File?, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownBlocks.parse(markdown) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(blocks) { _, block -> BlockView(block, assetsDir) }
    }
}

@Composable
private fun BlockView(block: MdBlock, assetsDir: File?) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 25.sp
                2 -> 21.sp
                3 -> 18.sp
                4 -> 16.sp
                else -> 15.sp
            }
            Text(
                text = inlineText(block.text),
                fontSize = size,
                fontWeight = FontWeight.Bold,
                lineHeight = (size.value * 1.35f).sp,
                modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)
            )
        }

        is MdBlock.Paragraph -> Text(
            text = inlineText(block.text),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 15.sp,
            lineHeight = 24.sp
        )

        is MdBlock.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }

        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 24.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = inlineText(block.text),
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item -> BulletRow(item) }
        }

        is MdBlock.Table -> TableView(block)

        is MdBlock.Image -> ImageBlock(block, assetsDir)

        is MdBlock.Math -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = block.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }

        MdBlock.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun BulletRow(item: MdItem) {
    val marker = if (item.marker.endsWith(".") || item.marker.endsWith(")")) {
        item.marker
    } else {
        "•"
    }
    Row(Modifier.padding(start = (item.level * 16).dp)) {
        Text(
            text = marker,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            modifier = Modifier.width(24.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = inlineText(item.text),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    val columns = table.header.size.coerceAtLeast(1)
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val cellWidth: Dp = if (columns <= 2) {
        ((screenWidth - 48).dp / columns)
    } else {
        140.dp
    }
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row {
            table.header.forEach { cell ->
                TableCell(cell, cellWidth, header = true)
            }
        }
        table.rows.forEach { row ->
            Row {
                for (index in 0 until columns) {
                    TableCell(row.getOrElse(index) { "" }, cellWidth, header = false)
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: Dp, header: Boolean) {
    Box(
        Modifier
            .width(width)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = inlineText(text),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ImageBlock(block: MdBlock.Image, assetsDir: File?) {
    val file = remember(block.path, assetsDir?.absolutePath) { resolveImage(block.path, assetsDir) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file?.absolutePath) {
        value = if (file == null) null else withContext(Dispatchers.IO) { decodeSampled(file) }
    }
    Column(Modifier.fillMaxWidth()) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = block.alt,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (file == null) "图片未找到：${block.path}" else "图片无法解码：${block.path}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (block.alt.isNotBlank()) {
            Text(
                text = block.alt,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
        }
    }
}

@Composable
private fun inlineText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            appendInline(text, SpanStyle(), linkColor, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    base: SpanStyle,
    linkColor: Color,
    codeBackground: Color
) {
    var i = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            withStyle(base) { append(plain.toString()) }
            plain.clear()
        }
    }

    while (i < text.length) {
        // 行内图片：只显示 alt
        if (text.startsWith("![", i)) {
            val close = text.indexOf("](", i)
            val end = if (close > 0) text.indexOf(')', close) else -1
            if (close > 0 && end > close) {
                flush()
                withStyle(base.merge(SpanStyle(fontStyle = FontStyle.Italic))) {
                    append(text.substring(i + 2, close))
                }
                i = end + 1
                continue
            }
        }

        // 粗体
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i) {
                flush()
                val merged = base.merge(SpanStyle(fontWeight = FontWeight.Bold))
                withStyle(merged) {
                    appendInline(text.substring(i + 2, end), merged, linkColor, codeBackground)
                }
                i = end + 2
                continue
            }
        }

        // 删除线
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i) {
                flush()
                val merged = base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                withStyle(merged) {
                    appendInline(text.substring(i + 2, end), merged, linkColor, codeBackground)
                }
                i = end + 2
                continue
            }
        }

        // 行内代码
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                flush()
                val merged = base.merge(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                )
                withStyle(merged) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // 链接
        if (text[i] == '[') {
            val close = text.indexOf("](", i)
            val end = if (close > 0) text.indexOf(')', close) else -1
            if (close > 0 && end > close) {
                flush()
                val label = text.substring(i + 1, close)
                val url = text.substring(close + 2, end)
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = base.merge(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    )
                ) {
                    appendInline(label, base, linkColor, codeBackground)
                }
                i = end + 1
                continue
            }
        }

        // 行内公式
        if (text[i] == '$') {
            val end = text.indexOf('$', i + 1)
            if (end > i + 1) {
                flush()
                val merged = base.merge(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                )
                withStyle(merged) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // 斜体
        val c = text[i]
        if (c == '*' || c == '_') {
            val prevOk = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = text.indexOf(c, i + 1)
            val nextOk = end > 0 && (end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
            if (prevOk && end > i + 1 && text[end - 1] != ' ' && nextOk) {
                flush()
                val merged = base.merge(SpanStyle(fontStyle = FontStyle.Italic))
                withStyle(merged) {
                    appendInline(text.substring(i + 1, end), merged, linkColor, codeBackground)
                }
                i = end + 1
                continue
            }
        }

        plain.append(text[i])
        i++
    }
    flush()
}

private fun resolveImage(path: String, assetsDir: File?): File? {
    var candidate = path.trim().substringBefore(' ').trim('<', '>')
    if (candidate.startsWith("http://") || candidate.startsWith("https://")) return null
    candidate = runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrDefault(candidate)
    candidate = candidate.removePrefix("./")
    val files = buildList {
        if (assetsDir != null) {
            add(File(assetsDir, candidate))
            assetsDir.parentFile?.let { add(File(it, candidate)) }
        }
        add(File(candidate))
    }
    return files.firstOrNull { it.isFile }
}

private fun decodeSampled(file: File, maxSize: Int = 1600): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}
