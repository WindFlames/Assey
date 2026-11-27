package io.savant.assey.data

import android.net.Uri
import io.savant.assey.AppContainer
import io.savant.assey.core.AppError
import io.savant.assey.core.errorKind
import io.savant.assey.core.userMessage
import io.savant.assey.domain.ParseProgress
import io.savant.assey.domain.ParseRequest
import io.savant.assey.domain.ParseResult
import io.savant.assey.domain.ParseStage
import io.savant.assey.domain.Settings
import io.savant.assey.domain.SourceKind
import io.savant.assey.domain.TaskStatus
import io.savant.assey.domain.TranslateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File

/**
 * 任务流水线：复制 → 选后端 → 解析 → 落盘 → （可选）翻译。
 * 所有 UI 交互只通过 TaskModel 的状态变化体现。
 */
class TaskRunner(private val container: AppContainer) {

    enum class Mode { PARSE, RESUME, TRANSLATE }

    suspend fun run(taskId: String, mode: Mode) {
        try {
            when (mode) {
                Mode.PARSE -> parse(taskId)
                Mode.RESUME -> resume(taskId)
                Mode.TRANSLATE -> translate(taskId)
            }
        } catch (e: CancellationException) {
            onCancelled(taskId)
            throw e
        } catch (e: Throwable) {
            // 阻塞网络调用被取消时可能以 IOException 形式抛出，这里统一按取消处理
            if (!currentCoroutineContext().isActive) {
                onCancelled(taskId)
                throw CancellationException("任务已取消")
            }
            val task = container.taskModel.get(taskId)
            val translationFailure = task?.markdownPath != null &&
                (mode == Mode.TRANSLATE ||
                    task.status == TaskStatus.TRANSLATING ||
                    task.translateStatus == TranslateStatus.RUNNING)
            if (translationFailure) {
                // 翻译失败不能把已解析的原文结果标记成整体失败
                container.taskModel.update(taskId, forcePersist = true) {
                    it.copy(
                        status = TaskStatus.DONE,
                        stageLabel = "翻译失败",
                        translateStatus = TranslateStatus.FAILED,
                        errorKind = e.errorKind(),
                        errorMessage = e.userMessage()
                    )
                }
            } else {
                container.taskModel.update(taskId, forcePersist = true) {
                    it.copy(
                        status = TaskStatus.FAILED,
                        stageLabel = "失败",
                        errorKind = e.errorKind(),
                        errorMessage = e.userMessage()
                    )
                }
            }
        }
    }

    private suspend fun parse(taskId: String) {
        val task = container.taskModel.get(taskId) ?: return
        val settings = container.settings.snapshot()
        val taskDir = container.taskDir(taskId).apply { mkdirs() }

        var localFile: File? = null
        var size = task.sizeBytes

        if (task.sourceKind == SourceKind.LOCAL_FILE) {
            val uri = task.sourceUri?.let(Uri::parse)
                ?: throw AppError.InvalidInput("缺少本地文件信息，请重新选择文件")
            val fileName = container.intake.normalizeName(task.title, null)
            val dest = File(taskDir, "source-${task.id}-$fileName")
            container.taskModel.update(taskId, forcePersist = true) {
                it.copy(status = TaskStatus.COPYING, stageLabel = "复制文件", errorMessage = null)
            }
            size = container.intake.copyTo(uri, dest, Settings.MAX_FILE_BYTES) { done, total ->
                container.taskModel.update(taskId) {
                    it.copy(bytesDone = done, bytesTotal = if (total > 0) total else it.bytesTotal)
                }
            }
            localFile = dest
            container.taskModel.update(taskId, forcePersist = true) {
                it.copy(localPath = dest.absolutePath, sizeBytes = size)
            }
        } else {
            val url = task.remoteUrl
            if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw AppError.InvalidInput("请填写有效的 http/https PDF 链接")
            }
        }

        if (size > Settings.MAX_FILE_BYTES) {
            throw AppError.FileTooLarge("文件大小 ${size / 1024 / 1024}MB，超过 20MB 限制")
        }

        val selection = container.parserFactory.select(
            kind = settings.parserKind,
            sizeBytes = size,
            isUrl = task.sourceKind == SourceKind.REMOTE_URL
        )
        val parser = selection.parser

        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(
                backendId = parser.id,
                backendName = container.parserFactory.nameOf(parser),
                supportsImages = parser.supportsImages,
                warning = selection.warning,
                status = TaskStatus.UPLOADING,
                stageLabel = "准备上传",
                errorKind = null,
                errorMessage = null,
                bytesDone = 0
            )
        }

        val request = ParseRequest(
            localFile = localFile,
            url = if (localFile == null) task.remoteUrl else null,
            options = task.options,
            fileName = container.intake.normalizeName(task.title, null),
            dataId = task.id
        )

        val result = parser.parse(request) { progress -> applyProgress(taskId, progress) }
        finishParse(taskId, result, autoTranslate = task.autoTranslate)
    }

    private suspend fun resume(taskId: String) {
        val task = container.taskModel.get(taskId) ?: return
        val parser = container.parserFactory.byId(task.backendId)
            ?: throw AppError.BackendError(message = "找不到原解析后端，请重新提交")
        val token = task.resumeToken
            ?: throw AppError.BackendError(message = "任务缺少恢复标识，请重新提交")

        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(status = TaskStatus.PARSING, stageLabel = "继续等待解析", errorMessage = null)
        }
        val result = parser.resume(token, task.id, task.options) { progress ->
            applyProgress(taskId, progress)
        }
        finishParse(taskId, result, autoTranslate = task.autoTranslate && task.translatedPath == null)
    }

    private suspend fun finishParse(taskId: String, result: ParseResult, autoTranslate: Boolean) {
        val task = container.taskModel.get(taskId) ?: return
        val taskDir = container.taskDir(taskId).apply { mkdirs() }
        val assetsDir = result.assetsDir
        val markdownFile = if (assetsDir != null) {
            File(assetsDir, "full.md")
        } else {
            File(taskDir, "full.md")
        }
        markdownFile.parentFile?.mkdirs()
        markdownFile.writeText(result.markdown)

        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.DONE,
                stageLabel = "解析完成",
                markdownPath = markdownFile.absolutePath,
                assetsPath = assetsDir?.absolutePath,
                backendId = result.backendId,
                errorKind = null,
                errorMessage = null,
                bytesDone = it.bytesTotal
            )
        }

        if (autoTranslate) {
            try {
                translate(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                container.taskModel.update(taskId, forcePersist = true) {
                    it.copy(
                        status = TaskStatus.DONE,
                        translateStatus = TranslateStatus.FAILED,
                        stageLabel = "翻译失败",
                        errorKind = e.errorKind(),
                        errorMessage = e.userMessage()
                    )
                }
            }
        }
    }

    private suspend fun translate(taskId: String) {
        val task = container.taskModel.get(taskId) ?: return
        val markdownPath = task.markdownPath
            ?: throw AppError.InvalidInput("还没有可翻译的 Markdown")
        val markdown = File(markdownPath).takeIf { it.isFile }?.readText()
            ?: throw AppError.StorageError("Markdown 文件已丢失，请重新解析")

        val translator = container.translatorFactory.create()
        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.TRANSLATING,
                translateStatus = TranslateStatus.RUNNING,
                stageLabel = "翻译中",
                pagesDone = 0,
                pagesTotal = 0,
                errorKind = null,
                errorMessage = null
            )
        }

        val translated = translator.translate(markdown, "中文") { done, total ->
            container.taskModel.update(taskId) {
                it.copy(
                    stageLabel = "翻译中 $done/$total",
                    pagesDone = done,
                    pagesTotal = total
                )
            }
        }

        currentCoroutineContext().ensureActive()
        val targetDir = task.assetsPath?.let(::File)
            ?: File(markdownPath).parentFile
            ?: container.taskDir(taskId)
        val target = File(targetDir, "translated.md")
        target.writeText(translated)

        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.DONE,
                translateStatus = TranslateStatus.DONE,
                translatedPath = target.absolutePath,
                stageLabel = "翻译完成",
                pagesDone = it.pagesTotal,
                errorKind = null,
                errorMessage = null
            )
        }
    }

    private fun applyProgress(taskId: String, progress: ParseProgress) {
        val current = container.taskModel.get(taskId) ?: return
        val status = when (progress.stage) {
            ParseStage.REQUESTING, ParseStage.PREPARING -> TaskStatus.UPLOADING
            ParseStage.UPLOADING -> TaskStatus.UPLOADING
            ParseStage.QUEUED, ParseStage.PARSING -> TaskStatus.PARSING
            ParseStage.DOWNLOADING -> TaskStatus.DOWNLOADING
            ParseStage.EXTRACTING -> TaskStatus.EXTRACTING
            ParseStage.FINISHED -> TaskStatus.DONE
        }
        val stageChanged = current.status != status
        val tokenChanged = progress.resumeToken != null && progress.resumeToken != current.resumeToken

        container.taskModel.update(taskId, forcePersist = stageChanged || tokenChanged) {
            it.copy(
                status = status,
                stageLabel = progress.message.ifBlank { status.label },
                pagesDone = if (progress.pagesTotal > 0) progress.pagesDone else it.pagesDone,
                pagesTotal = if (progress.pagesTotal > 0) progress.pagesTotal else it.pagesTotal,
                bytesDone = if (progress.bytesTotal > 0) progress.bytesDone else it.bytesDone,
                bytesTotal = if (progress.bytesTotal > 0) progress.bytesTotal else it.bytesTotal,
                resumeToken = progress.resumeToken ?: it.resumeToken
            )
        }
    }

    private fun onCancelled(taskId: String) {
        val task = container.taskModel.get(taskId)
        // 翻译阶段取消：保留已解析的 Markdown 与图片，只回退翻译状态
        val keepParsed = task != null &&
            task.markdownPath != null &&
            (task.status == TaskStatus.TRANSLATING || task.translateStatus == TranslateStatus.RUNNING)
        if (keepParsed) {
            container.taskModel.update(taskId, forcePersist = true) {
                it.copy(
                    status = TaskStatus.DONE,
                    stageLabel = "翻译已取消",
                    translateStatus = TranslateStatus.NONE,
                    errorKind = null,
                    errorMessage = null
                )
            }
            return
        }

        val taskDir = container.taskDir(taskId)
        runCatching { taskDir.deleteRecursively() }
        container.taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.CANCELED,
                stageLabel = "已取消",
                resumeToken = null,
                markdownPath = null,
                assetsPath = null,
                translatedPath = null,
                translateStatus = TranslateStatus.NONE,
                pagesDone = 0,
                pagesTotal = 0,
                bytesDone = 0,
                bytesTotal = 0,
                errorKind = null,
                errorMessage = null
            )
        }
    }
}
