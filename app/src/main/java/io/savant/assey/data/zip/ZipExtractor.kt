package io.savant.assey.data.zip

import io.savant.assey.core.AppError
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 安全解压：逐条校验规范化后的路径必须位于目标目录内（防 Zip Slip），
 * 同时限制条目数与解压总量，避免 zip 炸弹。
 */
class ZipExtractor {

    /** 从本地 zip 文件解压；解压完关闭文件流。 */
    fun extract(zipFile: File, destDir: File) {
        FileInputStream(zipFile).use { extract(it, destDir) }
    }

    /**
     * 直接从字节流解压——下载边写边解，不必先落一个 zip 文件到磁盘。
     *
     * 注意：**不关闭**调用方传入的流，所有权归调用方（HTTP 响应还要负责关闭）。
     */
    fun extract(input: InputStream, destDir: File) {
        val destRoot = destDir.canonicalFile
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw AppError.StorageError("无法创建解压目录")
        }
        val destPrefix = destRoot.path + File.separator
        var entries = 0
        var totalBytes = 0L

        val zis = ZipInputStream(BufferedInputStream(input, BUFFER_BYTES))
        while (true) {
            val next = try {
                zis.nextEntry
            } catch (e: IOException) {
                throw AppError.BackendError(message = "结果包损坏，请重新解析")
            }
            val entry = next ?: break
            entries++
            if (entries > MAX_ENTRIES) throw AppError.StorageError("结果包条目过多，已中止解压")

            val name = entry.name.replace('\\', '/')
            if (name.isBlank() || name.startsWith("/") || name.contains("..")) {
                throw AppError.StorageError("结果包包含非法路径，已中止解压")
            }
            val target = File(destRoot, name).canonicalFile
            if (!target.path.startsWith(destPrefix)) {
                throw AppError.StorageError("结果包包含路径穿越条目，已中止解压")
            }
            if (entry.isDirectory) {
                target.mkdirs()
                zis.closeEntry()
                continue
            }
            target.parentFile?.mkdirs()
            writeEntry(zis, target) { bytes ->
                totalBytes += bytes
                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw AppError.StorageError("结果包解压后体积过大，已中止")
                }
            }
            zis.closeEntry()
        }
    }

    /** 逐块搬运一个条目；读取异常算网络/结果包问题，写入异常算存储问题。 */
    private fun writeEntry(zis: ZipInputStream, target: File, onBytes: (Long) -> Unit) {
        try {
            target.outputStream().buffered(BUFFER_BYTES).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = try {
                        zis.read(buffer)
                    } catch (e: IOException) {
                        throw AppError.NetworkError("下载中断或结果包损坏，请重试")
                    }
                    if (read < 0) break
                    onBytes(read.toLong())
                    try {
                        out.write(buffer, 0, read)
                    } catch (e: IOException) {
                        throw AppError.StorageError("写入解压结果失败：${e.message ?: "存储不可写"}")
                    }
                }
            }
        } catch (e: AppError) {
            throw e
        } catch (e: IOException) {
            throw AppError.StorageError("写入解压结果失败：${e.message ?: "存储不可写"}")
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5000
        private const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
    }
}
