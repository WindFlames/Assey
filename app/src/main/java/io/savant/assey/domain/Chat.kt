package io.savant.assey.domain

/** 对话角色。序列化时按 name 存，读取失败回落到 USER。 */
enum class ChatRole { USER, ASSISTANT }

/**
 * 一条对话消息。
 *
 * 所有字段都给了默认值：Gson 读 JSON 时才能走无参构造，缺失字段也不会变成 null。
 * [error] 为 true 表示这条 assistant 消息存的是错误提示（不是模型输出），
 * UI 用不同配色展示，并允许点击重试。
 */
data class ChatMessage(
    val id: String = "",
    val role: ChatRole = ChatRole.USER,
    val content: String = "",
    val createdAt: Long = 0L,
    val error: Boolean = false
) {
    val isUser: Boolean get() = role == ChatRole.USER
}

/** 一次完整的会话。消息量大也不分页，直接整体存在 JSON 里。 */
data class ChatSession(
    val id: String = "",
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val messages: List<ChatMessage> = emptyList()
)

/** 发给模型的请求参数（已按当前设置解析完成的最终值）。 */
data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null
)
