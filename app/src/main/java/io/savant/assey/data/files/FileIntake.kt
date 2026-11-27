package io.savant.assey.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.savant.assey.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从 content:// / file:// 流式复制到 App 私有目录，边复制边回报进度。
 * 未知大小时按「最多 20MB」硬截断，绝不把超大文件放进内存。
 */
class FileIntake(private val context: Context) {

    fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun sizeOf(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).length() }
        }
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        }
        return null
    }

    suspend fun copyTo(
        uri: Uri,
        dest: File,
        maxBytes: Long,
        onProgress: (done: Long, total: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val knownSize = sizeOf(uri) ?: 0L
        if (knownSize > maxBytes) {
            throw AppError.FileTooLarge("文件大小 ${formatSize(knownSize)}，超过 20MB 限制")
        }
        dest.parentFile?.mkdirs()
        var total = 0L
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw AppError.InvalidInput("无法读取所选文件")
            input.use { source ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw AppError.FileTooLarge("文件超过 20MB 限制，已中止复制")
                        }
                        output.write(buffer, 0, read)
                        onProgress(total, knownSize)
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
        if (total == 0L) {
            dest.delete()
            throw AppError.InvalidInput("所选文件为空")
        }
        total
    }

    /** 保证文件名带扩展名，MinerU 要求 files[].name 有正确后缀。 */
    fun normalizeName(name: String?, mimeType: String?): String {
        val fallback = when {
            mimeType?.contains("pdf") == true -> "document.pdf"
            mimeType?.startsWith("image/") == true -> "image.png"
            else -> "document.pdf"
        }
        val raw = name?.trim().orEmpty().ifBlank { fallback }
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lower = safe.lowercase()
        val hasExtension = FILE_EXTENSIONS.any { lower.endsWith(it) }
        return if (hasExtension) safe else {
            when {
                mimeType?.contains("pdf") == true -> "$safe.pdf"
                mimeType == "image/jpeg" -> "$safe.jpg"
                mimeType?.startsWith("image/") == true -> "$safe.png"
                else -> "$safe.pdf"
            }
        }
    }

    companion object {
        private val FILE_EXTENSIONS = listOf(
            ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".tif", ".tiff"
        )
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
