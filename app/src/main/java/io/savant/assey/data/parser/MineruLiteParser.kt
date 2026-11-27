package io.savant.assey.data.parser

import io.savant.assey.core.AppError
import io.savant.assey.data.download.ResultDownloader
import io.savant.assey.domain.DocumentParser
import io.savant.assey.domain.DownloadMode
import io.savant.assey.domain.ParseOptions
import io.savant.assey.domain.ParseProgress
import io.savant.assey.domain.ParseRequest
import io.savant.assey.domain.ParseResult
import io.savant.assey.domain.ParseStage
import io.savant.assey.domain.Settings
import kotlinx.coroutines.delay
import java.io.File

/**
 * MinerU 轻量解析（免 Token，不返回图片）。
 * 限制：≤10MB、≤20 页、仅 PDF/图片/docx/pptx/xlsx。
 *
 * 网络部分全部走 [MineruApi] 的 Retrofit 接口，本类只负责阶段编排与轮询节奏；
 * 结果 Markdown 的下载交给 [ResultDownloader]（内置 / 系统下载两种方案）。
 */
class MineruLiteParser(
    private val api: MineruApi,
    private val taskDirProvider: (String) -> File,
    private val downloaderProvider: () -> ResultDownloader
) : DocumentParser {

    override val id: String = ID
    override val supportsImages: Boolean = false

    override suspend fun parse(
        request: ParseRequest,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        val localFile = request.localFile
        if (localFile != null && localFile.length() > Settings.LITE_MAX_FILE_BYTES) {
            throw AppError.FileTooLarge(
                "轻量解析仅支持 10MB 以内文件，请配置 MinerU Token 使用精准解析"
            )
        }

        onProgress(ParseProgress(ParseStage.REQUESTING, "提交轻量解析任务"))
        val ticket = if (localFile != null) {
            api.createLiteFileTask(fileBody(request))
        } else {
            api.createLiteUrlTask(urlBody(request))
        }

        if (localFile != null) {
            val uploadUrl = ticket.uploadUrl
                ?: throw AppError.BackendError(message = "轻量解析未返回上传地址")
            val total = localFile.length()
            onProgress(ParseProgress(ParseStage.UPLOADING, "上传文件", bytesTotal = total))
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

        onProgress(
            ParseProgress(ParseStage.PARSING, "已提交，等待解析", resumeToken = ticket.taskId)
        )
        return poll(ticket.taskId, request.dataId, onProgress)
    }

    override suspend fun resume(
        resumeToken: String,
        dataId: String,
        options: ParseOptions,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        onProgress(ParseProgress(ParseStage.PARSING, "继续等待解析", resumeToken = resumeToken))
        return poll(resumeToken, dataId, onProgress)
    }

    private suspend fun poll(
        taskId: String,
        dataId: String,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        var interval = INITIAL_INTERVAL_MS
        while (true) {
            val state = try {
                api.liteResult(taskId)
            } catch (e: AppError.RateLimited) {
                delay(e.retryAfterSeconds.coerceAtLeast(1) * 1000)
                continue
            }

            when (state.state) {
                "waiting-file" -> onProgress(
                    ParseProgress(ParseStage.QUEUED, "等待文件上传", resumeToken = taskId)
                )

                "uploading" -> onProgress(
                    ParseProgress(ParseStage.PARSING, "文件接收中", resumeToken = taskId)
                )

                "pending" -> onProgress(
                    ParseProgress(ParseStage.QUEUED, "排队中", resumeToken = taskId)
                )

                "running", "converting" -> onProgress(
                    ParseProgress(
                        ParseStage.PARSING,
                        if (state.state == "converting") "结果转换中" else "解析中",
                        resumeToken = taskId
                    )
                )

                "done" -> {
                    val markdownUrl = state.markdownUrl
                        ?: throw AppError.BackendError(message = "轻量解析完成但没有返回 Markdown 链接")
                    val dest = File(File(taskDirProvider(dataId), "download"), "full.md")
                    onProgress(
                        ParseProgress(
                            ParseStage.DOWNLOADING, downloadLabel(), resumeToken = taskId
                        )
                    )
                    downloaderProvider().download(markdownUrl, dest, taskKey = dataId) { done, total ->
                        onProgress(
                            ParseProgress(
                                ParseStage.DOWNLOADING,
                                downloadLabel(),
                                bytesDone = done,
                                bytesTotal = if (total > 0) total else 0L
                            )
                        )
                    }
                    val markdown = dest.takeIf { it.isFile }?.readText().orEmpty()
                    dest.delete()
                    if (markdown.isBlank()) {
                        throw AppError.BackendError(message = "轻量解析返回了空 Markdown")
                    }
                    onProgress(ParseProgress(ParseStage.FINISHED, "解析完成"))
                    return ParseResult(markdown = markdown, assetsDir = null, backendId = id)
                }

                "failed" -> throw state.toFailure()

                else -> onProgress(
                    ParseProgress(
                        ParseStage.PARSING, "解析中（${state.state}）", resumeToken = taskId
                    )
                )
            }

            delay(interval)
            interval = nextInterval(interval)
        }
    }

    private fun LiteTaskState.toFailure(): AppError =
        if (errCode != null) mineruError(errCode, errMsg)
        else AppError.BackendError(message = errMsg ?: "轻量解析失败")

    private fun downloadLabel(): String =
        if (downloaderProvider().mode == DownloadMode.EXTERNAL) "系统下载中" else "下载 Markdown"

    private fun fileBody(request: ParseRequest) = LiteFileRequest(
        fileName = request.fileName,
        language = request.options.language,
        enableTable = request.options.enableTable,
        isOcr = request.options.isOcr,
        enableFormula = request.options.enableFormula,
        pageRange = request.options.pageRanges.takeIf { it.isNotBlank() }
    )

    private fun urlBody(request: ParseRequest) = LiteUrlRequest(
        url = request.url.orEmpty(),
        fileName = request.fileName,
        language = request.options.language,
        enableTable = request.options.enableTable,
        isOcr = request.options.isOcr,
        enableFormula = request.options.enableFormula,
        pageRange = request.options.pageRanges.takeIf { it.isNotBlank() }
    )

    private fun nextInterval(current: Long): Long =
        (current * 1.2).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    companion object {
        const val ID = "mineru-lite"

        private const val INITIAL_INTERVAL_MS = 3000L
        private const val MIN_INTERVAL_MS = 3000L
        private const val MAX_INTERVAL_MS = 5000L
    }
}
