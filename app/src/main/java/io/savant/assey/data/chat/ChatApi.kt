package io.savant.assey.data.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.savant.assey.core.AppError
import io.savant.assey.core.net.ApiClient
import io.savant.assey.core.net.Auth
import io.savant.assey.core.net.BackendProfile
import io.savant.assey.core.net.Timeouts
import io.savant.assey.domain.ChatRequest

/**
 * OpenAI 兼容的 Chat Completions 流式客户端。
 *
 * 请求构造、鉴权、取消、错误映射都在 [ApiClient]；这里只负责
 * 「把对话压成 messages 数组」和「从 SSE 里抠出文本增量」。
 *
 * 不用 openai-java 的流式接口有两个原因：一是它的 chunk 模型对「只取文本增量」太重，
 * 二是这里要能直接读 SSE 原文，方便兼容各家（DeepSeek / vLLM / 中转）不完全一致的返回。
 */
class ChatApi(private val api: ApiClient) {

    /**
     * 发起流式请求，每拿到一段文本增量就回调 [onDelta]，返回完整回答。
     * 协程被取消时 [ApiClient] 会顺带取消请求，不会留下悬挂的 socket。
     */
    suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): String {
        val profile = BackendProfile(
            id = "chat",
            baseUrl = req.baseUrl,
            auth = Auth.Bearer({ req.apiKey }),
            // 流式输出可能长时间没有新数据，读超时必须关掉
            timeouts = Timeouts(readSeconds = 0)
        )
        return api.stream(profile, CHAT_PATH, buildBody(req)) { payload ->
            // 有些中转站会忽略 stream:true 直接回整段 JSON，这里兜一下
            val isEventStream = payload.contentType?.contains("event-stream", true) == true
            if (!isEventStream) {
                val content = parseDelta(payload.source.readUtf8())
                if (content.isNullOrEmpty()) {
                    throw AppError.BackendError(message = "模型返回的内容为空")
                }
                onDelta(content)
                return@stream content
            }

            val answer = StringBuilder()
            while (true) {
                val line = payload.source.readUtf8Line() ?: break
                // SSE：空行是事件分隔，`:` 开头是注释/心跳，`event:`/`id:` 用不到
                if (line.isEmpty() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = parseDelta(data) ?: continue
                if (delta.isEmpty()) continue
                answer.append(delta)
                onDelta(delta)
            }
            if (answer.isEmpty()) {
                throw AppError.BackendError(message = "模型没有返回任何内容，换个模型或稍后再试")
            }
            answer.toString()
        }
    }

    private fun buildBody(req: ChatRequest): JsonObject {
        val messages = JsonArray()
        req.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", prompt)
            })
        }
        req.messages.forEach { message ->
            // 错误占位和空的 assistant 占位都不该发给模型
            if (message.error) return@forEach
            if (!message.isUser && message.content.isBlank()) return@forEach
            messages.add(JsonObject().apply {
                addProperty("role", if (message.isUser) "user" else "assistant")
                addProperty("content", message.content)
            })
        }
        return JsonObject().apply {
            addProperty("model", req.model)
            addProperty("stream", true)
            addProperty("temperature", req.temperature)
            req.maxTokens?.let { addProperty("max_tokens", it) }
            add("messages", messages)
        }
    }

    /** 取一段增量：兼容 delta.content（流式）与 message.content（不支持流式时的整段返回）。 */
    private fun parseDelta(payload: String): String? {
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return null
        obj.getAsJsonObject("error")?.let { error ->
            val message = error.get("message")?.takeIf { !it.isJsonNull }?.asString
                ?: "模型返回错误"
            throw AppError.BackendError(message = message)
        }
        val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: return null
        choice.getAsJsonObject("delta")
            ?.get("content")?.takeIf { !it.isJsonNull }?.let { return it.asString }
        choice.getAsJsonObject("message")
            ?.get("content")?.takeIf { !it.isJsonNull }?.let { return it.asString }
        return null
    }

    private companion object {
        const val CHAT_PATH = "chat/completions"
    }
}
