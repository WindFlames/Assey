package io.savant.assey

import android.content.Context
import io.savant.assey.core.Http
import io.savant.assey.data.TaskRunner
import io.savant.assey.data.download.BuiltInDownloader
import io.savant.assey.data.download.SystemDownloader
import io.savant.assey.data.export.Exporter
import io.savant.assey.data.files.FileIntake
import io.savant.assey.data.model.TaskModel
import io.savant.assey.data.parser.MineruApi
import io.savant.assey.data.parser.MineruTokenChecker
import io.savant.assey.data.parser.ParserFactory
import io.savant.assey.data.store.SecureStore
import io.savant.assey.data.store.SettingsStore
import io.savant.assey.data.store.TaskStore
import io.savant.assey.data.translate.TranslatorFactory
import io.savant.assey.data.zip.ZipWriter
import io.savant.assey.domain.DownloadMode
import io.savant.assey.domain.TaskStatus
import io.savant.assey.service.TaskService
import java.io.File

/**
 * 手动依赖注入容器。整个 App 只有这一个单例，UI / Service 共享同一份状态。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val http = Http()
    private val secureStore = SecureStore()

    val settings = SettingsStore(appContext, secureStore)

    /** 持久化只认 JSON 读写；任务列表与状态在外面这层模型里。 */
    private val taskStore = TaskStore(appContext)
    val taskModel = TaskModel(taskStore)
    val intake = FileIntake(appContext)
    val exporter = Exporter(appContext)
    val zipWriter = ZipWriter()

    /** MinerU 的全部网络调用都走这一个 Retrofit 实例，Token 由拦截器按请求动态注入。 */
    val mineruApi = MineruApi(
        client = http.client,
        tokenProvider = { settings.snapshot().mineruToken }
    )
    val tokenChecker = MineruTokenChecker(mineruApi)

    /** 结果下载的两种方案：内置（App 进程内流式下载）/ 外部（系统 DownloadManager）。 */
    val builtInDownloader = BuiltInDownloader(mineruApi)
    val systemDownloader = SystemDownloader(appContext)

    val parserFactory = ParserFactory(
        api = mineruApi,
        tokenProvider = { settings.snapshot().mineruToken },
        taskDirProvider = { id -> taskDir(id) },
        downloaderProvider = {
            when (settings.snapshot().downloadMode) {
                DownloadMode.BUILT_IN -> builtInDownloader
                DownloadMode.EXTERNAL -> systemDownloader
            }
        }
    )

    val translatorFactory = TranslatorFactory(http) { settings.snapshot() }

    val runner = TaskRunner(this)

    private var recovered = false

    init {
        // 清理已被裁剪或删除的任务目录，避免长期使用后占满存储
        runCatching {
            val root = File(appContext.filesDir, "tasks")
            val known = taskModel.tasks.value.map { it.id }.toSet()
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in known) dir.deleteRecursively()
            }
        }
    }

    fun taskDir(taskId: String): File = File(File(appContext.filesDir, "tasks"), taskId)

    /**
     * 进程被杀后重启：只恢复「后端已受理」的轮询任务与未完成的翻译；
     * 上传/复制阶段被中断的任务无法安全续传，标记失败并提示重新提交。
     *
     * 只在进程内执行一次：Activity 因旋转重建时不能把仍在跑的任务误判为中断。
     */
    fun recoverInterruptedTasks() {
        if (recovered) return
        recovered = true
        taskModel.running().forEach { task ->
            when (task.status) {
                TaskStatus.PARSING, TaskStatus.DOWNLOADING -> {
                    if (task.resumeToken != null && task.backendId != null) {
                        TaskService.startResume(appContext, task.id)
                    } else {
                        markInterrupted(task.id)
                    }
                }

                TaskStatus.TRANSLATING -> {
                    if (task.markdownPath != null && File(task.markdownPath).isFile) {
                        TaskService.startTranslate(appContext, task.id)
                    } else {
                        markInterrupted(task.id)
                    }
                }

                else -> markInterrupted(task.id)
            }
        }
    }

    private fun markInterrupted(taskId: String) {
        taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.FAILED,
                stageLabel = "已中断",
                errorKind = "INTERRUPTED",
                errorMessage = "任务在后台被系统中断，请重新提交（已上传内容不会保留）"
            )
        }
    }

    fun deleteTask(taskId: String) {
        // 先请求停止正在跑的任务，再删除目录与元数据
        if (taskModel.get(taskId)?.isRunning == true) {
            TaskService.cancel(appContext, taskId)
        }
        runCatching { taskDir(taskId).deleteRecursively() }
        taskModel.remove(taskId)
    }

    /** 取消运行中的任务（由 Service 负责实际停止与清理）。 */
    fun cancelTask(taskId: String) {
        TaskService.cancel(appContext, taskId)
    }

    fun retryTask(taskId: String) {
        val task = taskModel.get(taskId) ?: return
        runCatching { taskDir(taskId).deleteRecursively() }
        taskModel.update(taskId, forcePersist = true) {
            it.copy(
                status = TaskStatus.DRAFT,
                stageLabel = "准备重新解析",
                resumeToken = null,
                markdownPath = null,
                assetsPath = null,
                translatedPath = null,
                translateStatus = io.savant.assey.domain.TranslateStatus.NONE,
                errorKind = null,
                errorMessage = null,
                pagesDone = 0,
                pagesTotal = 0,
                bytesDone = 0,
                bytesTotal = 0,
                localPath = null
            )
        }
        TaskService.startParse(appContext, taskId)
    }
}
