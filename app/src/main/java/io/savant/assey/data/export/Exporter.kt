package io.savant.assey.data.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.savant.assey.core.AppError
import io.savant.assey.data.zip.ZipWriter
import io.savant.assey.domain.TaskRecord
import java.io.File
import java.io.OutputStream

/**
 * 导出 / 分享。minSdk 33，统一走 MediaStore.Downloads（无需存储权限）。
 * 分享走 FileProvider + ACTION_SEND。
 */
class Exporter(private val context: Context) {

    private val zipWriter = ZipWriter()

    fun exportMarkdown(task: TaskRecord, translated: Boolean): String {
        val file = markdownFile(task, translated)
            ?: throw AppError.InvalidInput("没有可导出的 Markdown")
        val name = exportBaseName(task) + if (translated) ".zh.md" else ".md"
        saveToDownloads(name, "text/markdown") { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return name
    }

    fun exportZip(task: TaskRecord): String {
        val name = exportBaseName(task) + ".zip"
        val entries = zipEntries(task)
        // 直接流式写进系统下载目录：不落 cache 临时包，内存只占 8KB 缓冲
        saveToDownloads(name, "application/zip") { out ->
            zipWriter.write(entries, out)
        }
        return name
    }

    fun shareMarkdown(task: TaskRecord, translated: Boolean): Uri {
        val file = markdownFile(task, translated)
            ?: throw AppError.InvalidInput("没有可分享的 Markdown")
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, exportBaseName(task) + if (translated) ".zh.md" else ".md")
        file.copyTo(out, overwrite = true)
        return shareFile(out, "text/markdown")
    }

    fun shareZip(task: TaskRecord): Uri {
        // FileProvider 只服务真实文件，分享这一路必须落盘，写在 cache/share 下
        return shareFile(buildZipFile(task), "application/zip")
    }

    private fun shareFile(file: File, mime: String): Uri {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return uri
    }

    private fun markdownFile(task: TaskRecord, translated: Boolean): File? {
        val path = if (translated) task.translatedPath else task.markdownPath
        return path?.let(::File)?.takeIf { it.isFile }
    }

    /** 只算「打包什么」，不负责落在哪：导出走流，分享走临时文件，两份逻辑共用。 */
    private fun zipEntries(task: TaskRecord): List<Pair<File, String>> {
        val markdown = File(task.markdownPath ?: throw AppError.InvalidInput("没有可导出的 Markdown"))
        val root = task.assetsPath?.let(::File)
        val base = exportBaseName(task)
        val entries = ArrayList<Pair<File, String>>()
        entries.add(markdown to "$base.md")
        task.translatedPath?.let { translated ->
            val f = File(translated)
            if (f.isFile) entries.add(f to "$base.zh.md")
        }
        if (root != null && root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile }
                .filter { it.name != "full.md" && it.name != "translated.md" && it.name != "result.zip" }
                .filter { !it.name.startsWith("source-") }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace('\\', '/')
                    entries.add(file to relative)
                }
        }
        return entries
    }

    private fun buildZipFile(task: TaskRecord): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, exportBaseName(task) + ".zip")
        zipWriter.write(zipEntries(task), out)
        return out
    }

    private fun saveToDownloads(
        displayName: String,
        mime: String,
        writer: (OutputStream) -> Unit
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Assey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AppError.StorageError("无法写入系统下载目录")
        try {
            resolver.openOutputStream(uri)?.use(writer)
                ?: throw AppError.StorageError("无法写入系统下载目录")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e as? AppError ?: AppError.StorageError("导出失败：${e.message}")
        }
    }

    private fun exportBaseName(task: TaskRecord): String {
        val raw = task.title.substringBeforeLast('.').ifBlank { "paper" }
        return raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80)
    }
}
