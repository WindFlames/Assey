package io.savant.assey.ui

import io.savant.assey.AppContainer
import io.savant.assey.core.userMessage
import io.savant.assey.domain.ChatMessage
import io.savant.assey.domain.ChatRequest
import io.savant.assey.domain.ChatRole
import io.savant.assey.domain.ChatSession
import io.savant.assey.domain.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 对话的状态与副作用。
 *
 * 和任务链路不同，这里所有事都在主进程内存里完成：消息先写进 [ChatStore] 的 StateFlow
 * （UI 立刻能看到），流式增量不断覆盖最后一条 assistant 消息，落盘交给 store 去抖。
 */
class ChatController(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = container.chatStore
    private val api = container.chatApi

    val sessions = store.sessions
    val activeId = MutableStateFlow<String?>(null)
    val streaming = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)

    private var job: Job? = null

    private val active: ChatSession? get() = activeId.value?.let { store.get(it) }

    fun open(id: String) {
        if (id == activeId.value) return
        stop()
        activeId.value = id
    }

    /**
     * 从首页进来时调用：没有选中会话就自动打开最近一条。
     * 一条都没有时保持 null——界面显示欢迎页，用户发第一句时才真正建会话，
     * 免得每次点进来都多出一个空的「新对话」。
     */
    fun ensureVisible() {
        val current = activeId.value
        if (current != null && store.get(current) != null) return
        activeId.value = sessions.value.firstOrNull()?.id
    }

    fun newSession() {
        stop()
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            createdAt = now,
            updatedAt = now
        )
        store.upsert(session)
        activeId.value = session.id
    }

    fun delete(id: String) {
        if (id == activeId.value) stop()
        store.delete(id)
        if (id == activeId.value) activeId.value = sessions.value.firstOrNull()?.id
    }

    fun consumeToast() {
        toast.value = null
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || streaming.value) return
        val session = ensureSession()
        val now = System.currentTimeMillis()
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = content,
            createdAt = now
        )
        val isFirst = session.messages.none { it.isUser }
        store.mutate(session.id) { current ->
            current.copy(
                title = if (isFirst) titleOf(content) else current.title,
                messages = current.messages + user
            )
        }
        runStream(session.id, store.get(session.id)?.messages.orEmpty())
    }

    /** 重新生成：丢掉末尾的 assistant 消息，用已有上下文再请求一次。 */
    fun regenerate() {
        val session = active ?: return
        if (streaming.value) return
        val kept = session.messages.dropLastWhile { !it.isUser }
        if (kept.isEmpty() || !kept.last().isUser) return
        store.mutate(session.id) { it.copy(messages = kept) }
        runStream(session.id, kept)
    }

    fun stop() {
        job?.cancel()
        job = null
        streaming.value = false
    }

    /** 页面销毁时把未落盘的增量写下去。 */
    fun flush() = store.flush()

    private fun runStream(sessionId: String, history: List<ChatMessage>) {
        val placeholder = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            content = "",
            createdAt = System.currentTimeMillis()
        )
        store.mutate(sessionId) { it.copy(messages = it.messages + placeholder) }

        val settings = container.settings.snapshot()
        // 只带最近若干条，长对话不至于把上下文窗口一次打满
        val context = history.filterNot { it.error }.takeLast(MAX_CONTEXT_MESSAGES)

        streaming.value = true
        job = scope.launch {
            try {
                api.stream(settings.toChatRequest(context)) { delta ->
                    store.mutate(sessionId) { session ->
                        session.copy(
                            messages = session.messages.map {
                                if (it.id == placeholder.id) it.copy(content = it.content + delta) else it
                            }
                        )
                    }
                }
            } catch (e: CancellationException) {
                markStopped(sessionId, placeholder.id)
            } catch (e: Throwable) {
                val message = e.userMessage()
                store.mutate(sessionId) { session ->
                    session.copy(
                        messages = session.messages.map {
                            if (it.id == placeholder.id) {
                                it.copy(content = message, error = true)
                            } else {
                                it
                            }
                        }
                    )
                }
                toast.value = message
            } finally {
                streaming.value = false
                job = null
                store.flush()
            }
        }
    }

    /** 用户中途停止：保留已生成的内容，一个字都没出就标记成「已停止」。 */
    private fun markStopped(sessionId: String, messageId: String) {
        store.mutate(sessionId) { session ->
            session.copy(
                messages = session.messages.map {
                    if (it.id == messageId && it.content.isBlank()) {
                        it.copy(content = "已停止生成", error = true)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun ensureSession(): ChatSession {
        val current = active
        if (current != null) return current
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            createdAt = now,
            updatedAt = now
        )
        store.upsert(session)
        activeId.value = session.id
        return session
    }

    private fun titleOf(text: String): String {
        val single = text.replace('\n', ' ').trim()
        return if (single.length <= TITLE_LENGTH) single else single.take(TITLE_LENGTH) + "…"
    }

    private fun Settings.toChatRequest(history: List<ChatMessage>) = ChatRequest(
        // 留空则复用翻译服务的地址/Key，大多数中转站两边是同一套凭证
        baseUrl = chatBaseUrl.trim().ifBlank { translatorBaseUrl },
        apiKey = chatApiKey.trim().ifBlank { translatorApiKey },
        model = chatModel.trim().ifBlank { Settings.DEFAULT_CHAT_MODEL },
        systemPrompt = chatSystemPrompt,
        messages = history
    )

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 40
        const val TITLE_LENGTH = 18
    }
}
