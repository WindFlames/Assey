package io.savant.assey.data.model

import io.savant.assey.data.store.TaskStore
import io.savant.assey.domain.TaskRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务模型层（MVVM 的 Model）：内存里唯一一份任务列表 + 变更后落盘。
 *
 * 职责边界：
 *  - 持有 [tasks] 这个 StateFlow，UI 只订阅它，不直接读文件
 *  - 维护增删改、排序与容量上限（[MAX_TASKS]），保证列表形态始终一致
 *  - 每次变更委托 [TaskStore] 写盘，模型自己不认识 JSON
 *
 * 阶段语义（ParseStage → TaskStatus）仍留在 TaskRunner，模型层不掺业务判断。
 */
class TaskModel(private val store: TaskStore) {

    private val lock = Any()

    private val _tasks = MutableStateFlow(store.readAll())
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()

    fun get(id: String): TaskRecord? = _tasks.value.firstOrNull { it.id == id }

    /** 当前未结束的任务，供进程重启后的恢复逻辑使用。 */
    fun running(): List<TaskRecord> = _tasks.value.filter { it.isRunning }

    /** 新增或覆盖同 id 的任务，列表按创建时间倒序并裁剪到上限。 */
    fun add(record: TaskRecord) {
        synchronized(lock) {
            _tasks.value = (listOf(record) + _tasks.value.filterNot { it.id == record.id })
                .sortedByDescending { it.createdAt }
                .take(MAX_TASKS)
            store.writeAll(_tasks.value, force = true)
        }
    }

    /**
     * 就地更新一条任务。
     *
     * @param forcePersist 关键节点（阶段切换、恢复标识变化、终态）传 true 立即写盘；
     *                     高频进度回调传 false，由 [TaskStore] 做节流。
     * @return 更新后的记录；任务已不存在时返回 null。
     */
    fun update(
        id: String,
        forcePersist: Boolean = false,
        block: (TaskRecord) -> TaskRecord
    ): TaskRecord? {
        synchronized(lock) {
            val list = _tasks.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = block(list[index]).copy(updatedAt = System.currentTimeMillis())
            list[index] = updated
            _tasks.value = list
            store.writeAll(list, force = forcePersist)
            return updated
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            _tasks.value = _tasks.value.filterNot { it.id == id }
            store.writeAll(_tasks.value, force = true)
        }
    }

    companion object {
        /** 只保留最近 30 条，避免任务目录与 JSON 无限增长。 */
        private const val MAX_TASKS = 30
    }
}
