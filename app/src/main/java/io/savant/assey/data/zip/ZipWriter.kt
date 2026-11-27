package io.savant.assey.data.zip

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 把若干 (本地文件, zip 内相对路径) 打包，路径统一使用 '/'。 */
class ZipWriter {

    fun write(entries: List<Pair<File, String>>, output: File) {
        output.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
            val written = HashSet<String>()
            for ((file, name) in entries ) {
                if (!file.isFile) continue
                val path = name.replace('\\', '/').trimStart('/')
                if (path.isEmpty() || !written.add(path)) continue
                val entry = ZipEntry(path)
                entry.time = file.lastModified()
                zos.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
