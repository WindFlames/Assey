package io.savant.assey.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.savant.assey.AsseyApp
import io.savant.assey.data.TaskRunner
import io.savant.assey.domain.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务承载真正的解析/翻译流水线：
 *  - dataSync 类型，锁屏/切后台继续跑
 *  - 通知栏实时进度 + 取消
 *  - 进程被杀后由 AppContainer 重新拉起并续轮询
 */
class TaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workJob: Job? = null
    private var notifyJob: Job? = null
    private var foregroundStarted = false
    private var activeTaskId: String? = null
    private var runGeneration = 0L

    private val container get() = (application as AsseyApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_TASK_ID)
            // 只取消当前正在跑的任务，避免旧通知误杀新任务
            if (id == null || id == activeTaskId) {
                val job = workJob
                if (job != null) job.cancel() else stopSelf()
            }
            return START_NOT_STICKY
        }
        if (action != ACTION_START) return START_NOT_STICKY

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = runCatching {
            TaskRunner.Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: TaskRunner.Mode.PARSE.name)
        }.getOrDefault(TaskRunner.Mode.PARSE)

        val task = container.taskModel.get(taskId)
        val notification = if (task != null) {
            Notifications.build(this, task)
        } else {
            Notifications.fallback(this, "Assey", "准备任务…")
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            foregroundStarted = true
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限：直接把任务标记为失败，避免静默卡死
            container.taskModel.update(taskId, forcePersist = true) {
                it.copy(
                    status = TaskStatus.FAILED,
                    stageLabel = "无法启动后台任务",
                    errorKind = "FOREGROUND",
                    errorMessage = "系统拒绝启动后台任务，请保持 App 在前台后重试"
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        workJob?.cancel()
        notifyJob?.cancel()
        activeTaskId = taskId
        val generation = ++runGeneration

        workJob = scope.launch {
            try {
                container.runner.run(taskId, mode)
            } finally {
                // 只有最新一次启动才能收尾，避免旧任务（或同一任务的重试）杀掉新任务
                if (generation == runGeneration) {
                    activeTaskId = null
                    stopForegroundCompat()
                }
            }
        }

        notifyJob = scope.launch {
            var lastUpdate = 0L
            container.taskModel.tasks.collectLatest { list ->
                val current = list.firstOrNull { it.id == taskId } ?: return@collectLatest
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 500 || current.status.isTerminal) {
                    lastUpdate = now
                    Notifications.update(this@TaskService, current)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (foregroundStarted) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            Notifications.cancel(this)
        }
        stopSelf()
    }

    override fun onDestroy() {
        workJob?.cancel()
        notifyJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.savant.assey.action.START"
        const val ACTION_CANCEL = "io.savant.assey.action.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_MODE = "mode"

        fun startParse(context: Context, taskId: String) =
            start(context, taskId, TaskRunner.Mode.PARSE)

        fun startResume(context: Context, taskId: String) =
            start(context, taskId, TaskRunner.Mode.RESUME)

        fun startTranslate(context: Context, taskId: String) =
            start(context, taskId, TaskRunner.Mode.TRANSLATE)

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, TaskService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            // 取消不需要前台服务；直接投递到已运行的服务即可
            runCatching { context.startService(intent) }
        }

        private fun start(context: Context, taskId: String, mode: TaskRunner.Mode) {
            val intent = Intent(context, TaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_MODE, mode.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
