package io.savant.assey.data.zip

import io.savant.assey.core.AppError
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * 安全解压：逐条校验规范化后的路径必须位于目标目录内（防 Zip Slip），
 * 同时限制条目数与解压总量，避免 zip 炸弹。
 */
class ZipExtractor {

    fun extract(zipFile: File, destDir: File) {
        val destRoot = destDir.canonicalFile
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw AppError.StorageError("无法创建解压目录")
        }
        val destPrefix = destRoot.path + File.separator
        var entries = 0
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
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
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw AppError.StorageError("结果包解压后体积过大，已中止")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5000
        private const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
    }
}
