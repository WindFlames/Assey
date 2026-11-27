package io.savant.assey.data.zip

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把若干 (本地文件, zip 内相对路径) 打包，路径统一使用 '/'。
 *
 * @param excludedExtensions 写盘时丢弃的扩展名（小写、不带点）。默认排除 [DEFAULT_EXCLUDED_EXTENSIONS]，
 *   因为 assets 目录里的 tasks.json 之类的元数据与原始 PDF 属于中间产物，不该随包外发。
 *   确实需要原样打包时传 `emptySet()`。
 */
class ZipWriter(
    private val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {

    fun write(entries: List<Pair<File, String>>, output: File) {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { write(entries, it) }
    }

    /**
     * 直接写进调用方的流（MediaStore、Socket、内存都一样），不落地中间文件。
     * 只占用 8KB 缓冲，不需要按大小在内存和磁盘之间做取舍。
     *
     * 不关闭、只 finish + flush：流的生命周期归调用方所有。
     */
    fun write(entries: List<Pair<File, String>>, output: OutputStream) {
        val zos = ZipOutputStream(BufferedOutputStream(output, BUFFER_BYTES))
        val written = HashSet<String>()
        for ((file, name) in entries) {
            if (!file.isFile) continue
            val path = name.replace('\\', '/').trimStart('/')
            if (path.isEmpty()) continue
            if (isExcluded(path)) continue
            if (!written.add(path)) continue
            val entry = ZipEntry(path)
            entry.time = file.lastModified()
            zos.putNextEntry(entry)
            file.inputStream().use { input -> input.copyTo(zos, BUFFER_BYTES) }
            zos.closeEntry()
        }
        zos.finish()
        zos.flush()
    }

    /** 按 zip 内的 entry 名判断扩展名，大小写不敏感（PDF / Json 一样过滤）。 */
    private fun isExcluded(path: String): Boolean {
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot < path.lastIndexOf('/')) return false
        val ext = path.substring(dot + 1).lowercase()
        return ext.isNotEmpty() && ext in excludedExtensions
    }

    companion object {
        val DEFAULT_EXCLUDED_EXTENSIONS: Set<String> = setOf("json", "pdf")

        private const val BUFFER_BYTES = 8 * 1024
    }
}
