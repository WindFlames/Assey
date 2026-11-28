package io.savant.assey.ui

import android.content.Intent
import android.net.Uri
import io.savant.assey.AppContainer
import io.savant.assey.core.userMessage
import io.savant.assey.data.files.FileIntake
import io.savant.assey.data.store.SettingsStore
import io.savant.assey.domain.ParseOptions
import io.savant.assey.domain.Settings
import io.savant.assey.domain.SourceKind
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus
import io.savant.assey.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 轻量 Controller：持有 UI 事件与导航状态，真正的重活在 TaskService 里。
 * 所有业务状态都在 AppContainer 的单例 StateFlow 中，Activity 重建不会丢。
 */
class AppController(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings: StateFlow<Settings> = container.settings.state
    val tasks = container.taskModel.tasks
    // 对话状态按需创建：不进对话页就不读会话文件
    val chat by lazy { ChatController(container) }
    val route = MutableStateFlow("home")
    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val testResult = MutableStateFlow<String?>(null)

    fun openTask(id: String) {
        route.value = "task/$id"
    }

    fun openSettings() {
        route.value = "settings"
    }

    fun openChat() {
        chat.ensureVisible()
        route.value = "chat"
    }

    fun back() {
        route.value = "home"
    }

    fun consumeToast() {
        toast.value = null
    }

    fun clearTestResult() {
        testResult.value = null
    }

    fun defaultOptions(): ParseOptions = settings.value.toParseOptions()

    fun importUri(uri: Uri, options: ParseOptions = defaultOptions()) {
        scope.launch {
            try {
                runCatching {
                    container.appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val name = withContext(Dispatchers.IO) { container.intake.displayName(uri) }
                val mime = withContext(Dispatchers.IO) {
                    runCatching { container.appContext.contentResolver.getType(uri) }.getOrNull()
                }
                val size = withContext(Dispatchers.IO) { container.intake.sizeOf(uri) }
                if (size != null && size > Settings.MAX_FILE_BYTES) {
                    toast.value = "文件 ${FileIntake.formatSize(size)} 超过 20MB 限制，已拒绝上传"
                    return@launch
                }
                val title = container.intake.normalizeName(name, mime)
                val record = TaskRecord(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    sourceKind = SourceKind.LOCAL_FILE,
                    sourceUri = uri.toString(),
                    sizeBytes = size ?: 0L,
                    options = options,
                    status = TaskStatus.DRAFT,
                    autoTranslate = settings.value.autoTranslate
                )
                container.taskModel.add(record)
                route.value = "task/${record.id}"
                TaskService.startParse(container.appContext, record.id)
            } catch (e: Throwable) {
                toast.value = "导入失败：${e.userMessage()}"
            }
        }
    }

    fun importUrl(url: String, options: ParseOptions = defaultOptions()) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            toast.value = "请填写以 http:// 或 https:// 开头的 PDF 链接"
            return
        }
        scope.launch {
            val rawName = trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "remote.pdf" }
            val title = container.intake.normalizeName(rawName, "application/pdf")
            val record = TaskRecord(
                id = UUID.randomUUID().toString(),
                title = title,
                sourceKind = SourceKind.REMOTE_URL,
                remoteUrl = trimmed,
                options = options,
                status = TaskStatus.DRAFT,
                autoTranslate = settings.value.autoTranslate
            )
            container.taskModel.add(record)
            route.value = "task/${record.id}"
            runCatching { TaskService.startParse(container.appContext, record.id) }
                .onFailure { toast.value = "启动解析失败：${it.userMessage()}" }
        }
    }

    fun cancel(taskId: String) {
        container.cancelTask(taskId)
    }

    fun retry(taskId: String) {
        runCatching { container.retryTask(taskId) }
            .onFailure { toast.value = "重试失败：${it.userMessage()}" }
    }

    fun retranslate(taskId: String) {
        runCatching { TaskService.startTranslate(container.appContext, taskId) }
            .onFailure { toast.value = "启动翻译失败：${it.userMessage()}" }
    }

    fun delete(taskId: String) {
        container.deleteTask(taskId)
        route.value = "home"
    }

    fun exportMarkdown(taskId: String, translated: Boolean) {
        scope.launch {
            val task = container.taskModel.get(taskId) ?: return@launch
            try {
                val name = withContext(Dispatchers.IO) {
                    container.exporter.exportMarkdown(task, translated)
                }
                toast.value = "已保存到 下载/Assey/$name"
            } catch (e: Throwable) {
                toast.value = "导出失败：${e.userMessage()}"
            }
        }
    }

    fun exportZip(taskId: String) {
        scope.launch {
            val task = container.taskModel.get(taskId) ?: return@launch
            try {
                val name = withContext(Dispatchers.IO) { container.exporter.exportZip(task) }
                toast.value = "已保存到 下载/Assey/$name"
            } catch (e: Throwable) {
                toast.value = "导出失败：${e.userMessage()}"
            }
        }
    }

    fun shareMarkdown(taskId: String, translated: Boolean) {
        val task = container.taskModel.get(taskId) ?: return
        runCatching { container.exporter.shareMarkdown(task, translated) }
            .onFailure { toast.value = "分享失败：${it.userMessage()}" }
    }

    fun shareZip(taskId: String) {
        val task = container.taskModel.get(taskId) ?: return
        runCatching { container.exporter.shareZip(task) }
            .onFailure { toast.value = "分享失败：${it.userMessage()}" }
    }

    fun updateSettings(block: (Settings) -> Settings) {
        container.settings.update(block)
    }

    fun testToken(rawToken: String) {
        scope.launch {
            busy.value = true
            testResult.value = null
            val token = SettingsStore.sanitizeToken(rawToken)
            // 读取响应体是阻塞网络 IO，必须切到 IO 线程
            val result = withContext(Dispatchers.IO) { container.tokenChecker.check(token) }
            testResult.value = result.fold(
                onSuccess = { "Token 有效，可以使用精准解析 ✅" },
                onFailure = { "Token 校验失败：${it.userMessage()}" }
            )
            busy.value = false
        }
    }

    fun testTranslator(baseUrl: String, model: String, apiKey: String) {
        scope.launch {
            busy.value = true
            testResult.value = null
            try {
                val translator = container.translatorFactory.create(baseUrl, model, apiKey)
                val sample = "The quick brown fox jumps over the lazy dog."
                val output = withContext(Dispatchers.IO) {
                    withTimeout(90_000) {
                        translator.translate(sample, "中文") { _, _ -> }
                    }
                }
                testResult.value = "翻译服务可用 ✅ 示例：$output"
            } catch (e: Throwable) {
                testResult.value = "翻译服务不可用：${e.userMessage()}"
            } finally {
                busy.value = false
            }
        }
    }
}

fun Settings.toParseOptions(): ParseOptions = ParseOptions(
    model = defaultModel,
    pageRanges = "",
    isOcr = isOcr,
    enableFormula = enableFormula,
    enableTable = enableTable,
    language = defaultLanguage
)
