package io.savant.assey.data.download

import io.savant.assey.data.parser.MineruApi
import io.savant.assey.domain.DownloadMode
import java.io.File
import java.io.InputStream

/**
 * 解析结果下载的统一入口。
 *
 * 两种实现语义一致：调用返回时 [dest] 一定已经是一个完整的本地文件；
 * 差别只在传输由谁完成（App 进程 / 系统 DownloadManager）。
 */
interface ResultDownloader {

    val mode: DownloadMode

    /** 是否能把响应流直接交给上层。系统 DownloadManager 必须落盘，只有内置方案支持。 */
    val canStream: Boolean get() = false

    /**
     * @param url 预签名 CDN 地址（MinerU 的结果包或 Markdown）
     * @param dest App 内的落地文件
     * @param taskKey 任务 id，外部下载用它生成系统侧的独立目录，避免多次任务互相覆盖
     * @param onProgress 进度回调；total 未知时为 -1
     */
    suspend fun download(
        url: String,
        dest: File,
        taskKey: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File

    /**
     * 不落盘的下载：字节流直接交给 [consume]，它在 IO 线程执行，返回即表示响应读完。
     * 只有 [canStream] 为 true 时可用。
     */
    suspend fun stream(
        url: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        consume: (InputStream) -> Unit
    ) {
        throw UnsupportedOperationException("${mode.name} 不支持流式下载")
    }
}

/**
 * 内置方案：走 [MineruApi] 的 `@Streaming` 下载，进度直接从字节流读出来。
 * 优点是进度细、取消即时；缺点是必须由 App 进程完成，进程被杀就断。
 */
class BuiltInDownloader(
    private val api: MineruApi
) : ResultDownloader {

    override val mode: DownloadMode = DownloadMode.BUILT_IN

    override val canStream: Boolean = true

    override suspend fun download(
        url: String,
        dest: File,
        taskKey: String,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        api.download(url, dest, onProgress)
        return dest
    }

    override suspend fun stream(
        url: String,
        onProgress: (done: Long, total: Long) -> Unit,
        consume: (InputStream) -> Unit
    ) {
        api.downloadStream(url, onProgress, consume)
    }
}
