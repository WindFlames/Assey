package io.savant.assey.data.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.savant.assey.domain.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.reflect.Type

/**
 * 会话持久化：整体读写 `filesDir/chat/sessions.json`。
 *
 * 与任务列表不同，对话没有跨进程并发写入的需求（只由 ChatController 改），
 * 所以不需要文件锁；但流式输出时内容每秒会变几十次，必须做写盘合并，
 * 否则会把磁盘 IO 打满 —— 这里用「取消上一个、延迟 500ms 再写」的方式去抖。
 */
class ChatStore(context: Context) {

    private val file = File(File(context.filesDir, "chat"), "sessions.json")
    private val gson: Gson = GsonBuilder().create()
    private val listType: Type = object : TypeToken<List<ChatSession>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private var pendingWrite: Job? = null

    fun get(id: String): ChatSession? = _sessions.value.firstOrNull { it.id == id }

    fun upsert(session: ChatSession) {
        val exists = _sessions.value.any { it.id == session.id }
        val next = if (exists) {
            _sessions.value.map { if (it.id == session.id) session else it }
        } else {
            listOf(session) + _sessions.value
        }
        publish(next)
    }

    fun mutate(id: String, block: (ChatSession) -> ChatSession) {
        var changed = false
        val now = System.currentTimeMillis()
        val next = _sessions.value.map { session ->
            if (session.id != id) {
                session
            } else {
                changed = true
                // 更新时间戳统一在这里做，历史列表才能按最近活跃排序
                block(session).copy(updatedAt = now)
            }
        }
        if (changed) publish(next)
    }

    fun delete(id: String) {
        publish(_sessions.value.filterNot { it.id == id })
    }

    /** 立即落盘（流式结束、页面销毁时调用），跳过防抖。 */
    fun flush() {
        pendingWrite?.cancel()
        val snapshot = _sessions.value
        scope.launch { writeSafely(snapshot) }
    }

    private fun publish(list: List<ChatSession>) {
        _sessions.value = list.sortedByDescending { it.updatedAt }
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(500)
            writeSafely(_sessions.value)
        }
    }

    private fun writeSafely(list: List<ChatSession>) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再原子替换，避免写一半被杀进程留下坏 JSON
            val tmp = File(file.parentFile, "sessions.json.tmp")
            tmp.writeText(gson.toJson(list, listType))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): List<ChatSession> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            gson.fromJson<List<ChatSession>>(text, listType).orEmpty()
        }.getOrElse {
            // 文件损坏就备份一份，别让用户的历史对话被下次写入直接覆盖掉
            runCatching { file.renameTo(File(file.parentFile, "sessions.corrupt.json")) }
            emptyList()
        }
    }
}
