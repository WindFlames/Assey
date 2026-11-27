package io.savant.assey.data.store

import android.content.Context
import io.savant.assey.domain.TaskRecord
import java.io.File

/**
 * 任务元数据落盘：JSON 文件 + 原子替换。
 *
 * 只做「读文件 / 写文件」，不持有内存状态、不做业务判断：
 * 状态与增删改由 [io.savant.assey.data.model.TaskModel] 维护，
 * 序列化交给 [TaskJson]（Gson），本类不认识任何字段。
 * 写盘带 400ms 节流，避免上传/解析时每次进度回调都触发磁盘 IO。
 */
class TaskStore(context: Context) {

    private val file = File(context.filesDir, "tasks.json")
    private val lock = Any()
    private var lastWrite = 0L

    /** 读取全量任务；文件缺失或损坏时返回空列表，不让坏数据拖垮启动。 */
    fun readAll(): List<TaskRecord> {
        if (!file.exists()) return emptyList()
        return try {
            TaskJson.decode(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 覆盖写入全量任务。
     *
     * @param force 关键节点（新增、删除、阶段切换、恢复中断）传 true 立即落盘；
     *              高频进度回调传 false，按 [WRITE_THROTTLE_MS] 节流。
     */
    fun writeAll(tasks: List<TaskRecord>, force: Boolean = false) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastWrite <= WRITE_THROTTLE_MS) return
            lastWrite = now

            val json = TaskJson.encode(tasks)
            val tmp = File(file.parentFile, "tasks.json.tmp")
            try {
                tmp.writeText(json)
                // Android/Linux 上 rename 会原子替换已存在的文件，无需先删除旧文件
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                // 元数据写失败不应打断正在运行的任务
            }
        }
    }

    companion object {
        /** 进度类更新最多 400ms 落盘一次。 */
        private const val WRITE_THROTTLE_MS = 400L
    }
}
