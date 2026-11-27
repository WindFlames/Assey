package io.savant.assey.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.savant.assey.MainActivity
import io.savant.assey.R
import io.savant.assey.domain.TaskRecord
import io.savant.assey.domain.TaskStatus

object Notifications {

    const val CHANNEL_ID = "assay_tasks"
    const val NOTIFICATION_ID = 0x4153

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "解析与翻译任务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示论文解析/翻译进度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, task: TaskRecord): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_TASK, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, TaskService::class.java).apply {
                action = TaskService.ACTION_CANCEL
                putExtra(TaskService.EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Assey · ${task.displayStatus}")
            .setContentText(contentText(task))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText(task)))
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(task.isRunning)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (task.isRunning) {
            builder.addAction(0, "取消", cancelIntent)
            val fraction = task.progressFraction
            if (task.status == TaskStatus.TRANSLATING && task.pagesTotal > 0) {
                builder.setProgress(task.pagesTotal, task.pagesDone, false)
            } else if (fraction != null) {
                builder.setProgress(1000, (fraction * 1000).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    fun update(context: Context, task: TaskRecord) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, build(context, task))
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun contentText(task: TaskRecord): String {
        val builder = StringBuilder()
        if (task.stageLabel.isNotBlank()) builder.append(task.stageLabel)
        if (task.pagesTotal > 0) {
            if (builder.isNotEmpty()) builder.append(" · ")
            builder.append("${task.pagesDone}/${task.pagesTotal} 页")
        } else if (task.bytesTotal > 0 && task.status == TaskStatus.UPLOADING) {
            if (builder.isNotEmpty()) builder.append(" · ")
            builder.append("${task.bytesDone * 100 / task.bytesTotal}%")
        }
        if (builder.isEmpty()) builder.append(task.title)
        return builder.toString()
    }

    fun fallback(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
}
