package io.savant.assey.data.parser

import io.savant.assey.core.AppError
import io.savant.assey.data.download.ResultDownloader
import io.savant.assey.data.zip.ZipExtractor
import io.savant.assey.domain.DocumentParser
import io.savant.assey.domain.DownloadMode
import io.savant.assey.domain.ParseOptions
import io.savant.assey.domain.ParseProgress
import io.savant.assey.domain.ParseRequest
import io.savant.assey.domain.ParseResult
import io.savant.assey.domain.ParseStage
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * MinerU 精准解析（需要 Token，返回 Markdown + 图片）。
 * 专有参数（model_version / page_ranges / data_id ...）全部封装在本类内部。
 *
 * 网络部分全部走 [MineruApi] 的 Retrofit 接口，本类只负责阶段编排与轮询节奏。
 */
class MineruPrecisionParser(
    private val api: MineruApi,
    private val tokenProvider: () -> String,
    private val taskDirProvider: (String) -> File,
    private val downloaderProvider: () -> ResultDownloader
) : DocumentParser {

    override val id: String = ID
    override val supportsImages: Boolean = true

    override suspend fun parse(
        request: ParseRequest,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        // 没 Token 就不必发请求，直接给出可操作的提示
        requireToken()
        val localFile = request.localFile

        val ticket = if (localFile != null) {
            onProgress(ParseProgress(ParseStage.REQUESTING, "申请上传地址"))
            api.createUploadBatch(uploadBody(request))
        } else {
            onProgress(ParseProgress(ParseStage.REQUESTING, "提交远程链接"))
            api.createUrlBatch(urlBody(request))
        }

        if (localFile != null) {
            val uploadUrl = ticket.uploadUrl
                ?: throw AppError.BackendError(message = "MinerU 未返回上传地址")
            val total = localFile.length()
            onProgress(
                ParseProgress(ParseStage.UPLOADING, "上传文件", bytesDone = 0L, bytesTotal = total)
            )
            // 预签名 PUT：不带 Authorization，也不设置 Content-Type
            api.uploadFile(uploadUrl, localFile) { done, size ->
                onProgress(
                    ParseProgress(
                        ParseStage.UPLOADING,
                        "上传文件",
                        bytesDone = done,
                        bytesTotal = if (size > 0) size else total
                    )
                )
            }
        }

        // 只有上传成功/URL 受理后才允许重启续跑
        onProgress(
            ParseProgress(ParseStage.PARSING, "已提交，等待解析", resumeToken = ticket.batchId)
        )
        return poll(ticket.batchId, request.dataId, onProgress)
    }

    override suspend fun resume(
        resumeToken: String,
        dataId: String,
        options: ParseOptions,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        requireToken()
        onProgress(ParseProgress(ParseStage.PARSING, "继续等待解析", resumeToken = resumeToken))
        return poll(resumeToken, dataId, onProgress)
    }

    private suspend fun poll(
        batchId: String,
        dataId: String,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        var interval = INITIAL_INTERVAL_MS
        while (true) {
            val results = try {
                api.batchResult(batchId)
            } catch (e: AppError.RateLimited) {
                delay((e.retryAfterSeconds.coerceAtLeast(1) * 1000).milliseconds)
                continue
            }

            val item = pickResult(results, dataId)
            if (item == null) {
                onProgress(ParseProgress(ParseStage.QUEUED, "排队中", resumeToken = batchId))
                delay(interval.milliseconds)
                interval = nextInterval(interval)
                continue
            }

            when (item.state) {
                "waiting-file" -> onProgress(
                    ParseProgress(ParseStage.QUEUED, "等待文件上传", resumeToken = batchId)
                )

                "pending" -> onProgress(
                    ParseProgress(
                        ParseStage.QUEUED, "排队中", item.pagesDone, item.pagesTotal,
                        resumeToken = batchId
                    )
                )

                "running", "converting" -> onProgress(
                    ParseProgress(
                        ParseStage.PARSING,
                        if (item.state == "converting") "结果转换中" else "解析中",
                        item.pagesDone,
                        item.pagesTotal,
                        resumeToken = batchId
                    )
                )

                "done" -> {
                    val zipUrl = item.zipUrl
                        ?: throw AppError.BackendError(message = "解析完成但没有返回结果包")
                    onProgress(
                        ParseProgress(
                            ParseStage.DOWNLOADING, downloadLabel(), item.pagesDone,
                            item.pagesTotal, resumeToken = batchId
                        )
                    )
                    return downloadAndExtract(zipUrl, dataId, onProgress)
                }

                "failed" -> throw AppError.BackendError(
                    message = item.errMsg ?: "MinerU 解析失败"
                )

                else -> onProgress(
                    ParseProgress(
                        ParseStage.PARSING, "解析中（${item.state}）", item.pagesDone,
                        item.pagesTotal, resumeToken = batchId
                    )
                )
            }

            delay(interval)
            interval = nextInterval(interval)
        }
    }

    private fun pickResult(results: List<MineruResultItem>, dataId: String): MineruResultItem? {
        if (results.isEmpty()) return null
        return results.firstOrNull { it.dataId == dataId } ?: results.first()
    }

    private suspend fun downloadAndExtract(
        zipUrl: String,
        dataId: String,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        val destDir = File(taskDirProvider(dataId), "result")
        val parent = destDir.parentFile ?: destDir
        parent.mkdirs()
        destDir.deleteRecursively()
        destDir.mkdirs()

        val onBytes: (Long, Long) -> Unit = { done, total ->
            onProgress(
                ParseProgress(
                    ParseStage.DOWNLOADING,
                    downloadLabel(),
                    bytesDone = done,
                    bytesTotal = if (total > 0) total else 0L
                )
            )
        }

        val downloader = downloaderProvider()
        try {
            if (downloader.canStream) {
                // 内置方案：响应流直接进 ZipInputStream，result.zip 不落盘
                downloader.stream(zipUrl, onBytes) { input ->
                    ZipExtractor().extract(input, destDir)
                }
            } else {
                // 系统下载：传输由 DownloadManager 完成，拿回来的就是文件，只能先落盘再解压
                val zipFile = File(parent, "result.zip")
                downloader.download(zipUrl, zipFile, taskKey = dataId, onProgress = onBytes)
                onProgress(ParseProgress(ParseStage.EXTRACTING, "解压结果"))
                ZipExtractor().extract(zipFile, destDir)
                zipFile.delete()
            }
        } catch (e: Throwable) {
            // 半套解压结果没有任何用处，失败即清干净，避免占着空间
            destDir.deleteRecursively()
            throw e
        }

        val markdownFile = findMarkdown(destDir)
            ?: throw AppError.BackendError(message = "结果包中未找到 Markdown 文件")
        val markdown = markdownFile.readText()
        onProgress(ParseProgress(ParseStage.FINISHED, "解析完成"))
        return ParseResult(
            markdown = markdown,
            assetsDir = markdownFile.parentFile,
            backendId = id
        )
    }

    private fun findMarkdown(dir: File): File? {
        val candidates = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .toList()
        return candidates.firstOrNull { it.name.equals("full.md", ignoreCase = true) }
            ?: candidates.firstOrNull { !it.name.contains("content_list", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    // ---------------- 请求体组装 ----------------

    private fun uploadBody(request: ParseRequest): UploadBatchRequest {
        val options = request.options
        return UploadBatchRequest(
            enableFormula = options.enableFormula,
            enableTable = options.enableTable,
            language = options.language,
            modelVersion = options.model.value,
            files = listOf(
                UploadFileEntry(
                    name = request.fileName,
                    dataId = request.dataId,
                    isOcr = options.isOcr,
                    pageRanges = options.pageRanges.takeIf { it.isNotBlank() }
                )
            )
        )
    }

    private fun urlBody(request: ParseRequest): UrlBatchRequest {
        val options = request.options
        return UrlBatchRequest(
            enableFormula = options.enableFormula,
            enableTable = options.enableTable,
            language = options.language,
            modelVersion = options.model.value,
            files = listOf(
                UrlFileEntry(
                    url = request.url.orEmpty(),
                    name = request.fileName,
                    dataId = request.dataId,
                    isOcr = options.isOcr,
                    pageRanges = options.pageRanges.takeIf { it.isNotBlank() }
                )
            )
        )
    }

    /** 两种下载方案的阶段文案：边走边解 vs 系统通知栏。 */
    private fun downloadLabel(): String {
        val downloader = downloaderProvider()
        return when {
            downloader.mode == DownloadMode.EXTERNAL -> "系统下载中"
            downloader.canStream -> "下载并解压"
            else -> "下载结果"
        }
    }

    private fun requireToken(): String {
        val token = tokenProvider().trim()
        if (token.isEmpty()) {
            throw AppError.AuthError("未配置 MinerU Token，请到设置页填写")
        }
        return token
    }

    private fun nextInterval(current: Long): Long =
        (current * 1.2).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    companion object {
        const val ID = "mineru-precision"

        private const val INITIAL_INTERVAL_MS = 3000L
        private const val MIN_INTERVAL_MS = 3000L
        private const val MAX_INTERVAL_MS = 5000L
    }
}
