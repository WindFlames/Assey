package io.savant.assey.data.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import io.savant.assey.core.AppError
import io.savant.assey.domain.DownloadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 外部方案：把传输交给系统 DownloadManager。
 *
 * 流程：
 *  1. `enqueue` 到应用专属外部目录（`Android/data/<pkg>/files/Download/assey/<taskId>/`），无需存储权限；
 *  2. 轮询系统侧状态，把字节进度回吐给上层（任务页、通知栏照常显示）；
 *  3. 下载完成后复制回 App 私有任务目录并删除系统侧副本。
 *
 * 进程被杀时 DownloadManager 会继续下载；重启后任务会按 resumeToken 重新走一遍
 * （同 taskKey 的残留文件会先被清掉，不会互相覆盖或报 FILE_ALREADY_EXISTS）。
 * 任务取消时协程被中断，`finally` 里 remove 掉系统任务，通知随之消失。
 */
class SystemDownloader(
    private val context: Context
) : ResultDownloader {

    override val mode: DownloadMode = DownloadMode.EXTERNAL

    private val manager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    override suspend fun download(
        url: String,
        dest: File,
        taskKey: String,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        val subPath = subPathOf(taskKey, dest.name)
        val id = withContext(Dispatchers.IO) {
            // 进程重启后重下同一个任务时，先把上一次仍在跑的记录清掉，避免两个任务写同一个文件
            removeStale(subPath)
            // 上一次（含进程被杀前）的残留会触发 ERROR_FILE_ALREADY_EXISTS，再清掉文件本身
            systemFile(subPath)?.let { it.parentFile?.mkdirs(); it.delete() }
            try {
                manager.enqueue(request(url, subPath))
            } catch (e: Exception) {
                throw AppError.NetworkError(
                    "无法提交系统下载：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }

        try {
            await(id, dest, onProgress)
            return dest
        } finally {
            // 成功（已复制）、失败、取消都清理系统侧记录与副本，避免留下垃圾文件
            runCatching { manager.remove(id) }
        }
    }

    /** 同一个 taskKey 的旧下载记录（未完成）全部移除，避免并发写同一个目标文件。 */
    private fun removeStale(subPath: String) {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED
        )
        val ids = ArrayList<Long>()
        runCatching {
            manager.query(query).use { cursor ->
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (idIndex < 0 || uriIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val local = cursor.getString(uriIndex) ?: continue
                    if (local.endsWith(subPath)) ids.add(cursor.getLong(idIndex))
                }
            }
        }
        ids.forEach { id -> runCatching { manager.remove(id) } }
    }

    private fun request(url: String, subPath: String): DownloadManager.Request =
        DownloadManager.Request(Uri.parse(url))
            .setTitle("Assey 解析结果")
            .setDescription("正在下载解析结果…")
            .setMimeType(if (subPath.endsWith(".md")) "text/markdown" else "application/zip")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                subPath
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

    private suspend fun await(
        id: Long,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        while (true) {
            val snapshot = query(id)
                ?: throw AppError.NetworkError("系统下载任务已丢失，请重新提交")
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    copyTo(snapshot.uri ?: manager.getUriForDownloadedFile(id), dest)
                    onProgress(dest.length(), dest.length())
                    return@withContext
                }

                DownloadManager.STATUS_FAILED -> throw failureOf(snapshot.reason)

                DownloadManager.STATUS_PAUSED ->
                    throw AppError.NetworkError("系统下载已暂停（网络不可用），请重试")

                else -> onProgress(snapshot.done, snapshot.total)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun query(id: Long): Snapshot? {
        val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }
            .getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return Snapshot(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                done = it.longOrZero(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                total = it.longOrZero(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                uri = it.stringOrNull(DownloadManager.COLUMN_LOCAL_URI)?.let(Uri::parse)
            )
        }
    }

    private fun copyTo(source: Uri?, dest: File) {
        if (source == null) throw AppError.StorageError("系统下载完成但找不到文件")
        dest.parentFile?.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw AppError.StorageError("无法读取系统下载的文件")
            input.use { from ->
                dest.outputStream().buffered().use { to -> from.copyTo(to) }
            }
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError.StorageError("读取系统下载文件失败：${e.message ?: "未知错误"}")
        }
        if (!dest.isFile || dest.length() == 0L) {
            throw AppError.StorageError("系统下载的文件为空，请重试")
        }
    }

    private fun failureOf(reason: Int): AppError = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE ->
            AppError.StorageError("存储空间不足，系统下载失败")

        DownloadManager.ERROR_FILE_ALREADY_EXISTS,
        DownloadManager.ERROR_FILE_ERROR ->
            AppError.StorageError("系统下载写入文件失败，请重试")

        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            AppError.NetworkError("下载地址返回异常，请稍后重试")

        DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
            AppError.NetworkError("下载地址跳转过多，请稍后重试")

        DownloadManager.ERROR_CANNOT_RESUME ->
            AppError.NetworkError("系统下载无法续传，请重试")

        else -> AppError.NetworkError("系统下载失败（code=$reason），请重试")
    }

    /** `Android/data/<pkg>/files/Download/assey/<taskKey>/<fileName>` */
    private fun subPathOf(taskKey: String, fileName: String): String {
        val safeKey = taskKey.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "task" }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "result.zip" }
        return "assey/$safeKey/$safeName"
    }

    private fun systemFile(subPath: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, subPath) }

    private data class Snapshot(
        val status: Int,
        val reason: Int,
        val done: Long,
        val total: Long,
        val uri: Uri?
    )

    private companion object {
        const val POLL_INTERVAL_MS = 700L
    }
}

private fun Cursor.longOrZero(column: String): Long {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else 0L
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}
