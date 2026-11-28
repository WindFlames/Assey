package io.savant.assey.data.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.savant.assey.core.AppError
import io.savant.assey.core.Http
import io.savant.assey.core.httpError
import io.savant.assey.core.mapIoError
import io.savant.assey.domain.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OpenAI 兼容的 Chat Completions 流式客户端。
 *
 * 不用 openai-java 的流式接口有两个原因：一是它的 chunk 模型对「只取文本增量」太重，
 * 二是这里要能直接读 SSE 原文，方便兼容各家（DeepSeek / vLLM / 中转）不完全一致的返回。
 * 认证、重试、连接池仍复用 [Http] 里的共享 OkHttpClient。
 */
class ChatApi(private val http: Http) {

    /** 流式输出可能长时间没有新数据，读超时必须关掉；连接超时保持 30s。 */
    private val client = http.client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * 发起流式请求，每拿到一段文本增量就回调 [onDelta]，返回完整回答。
     * 协程被取消时会顺带 `call.cancel()`，不会留下悬挂的 socket。
     */
    suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(req.baseUrl))
                .post(buildBody(req).toString().toRequestBody(JSON))
                .header("Accept", "text/event-stream")
                .apply {
                    // 本地 vLLM / Ollama 之类常常不校验 Key，空值就不要发 Authorization
                    if (req.apiKey.isNotBlank()) header("Authorization", "Bearer ${req.apiKey}")
                }
                .build()

            val call = client.newCall(request)
            coroutineContext.job.invokeOnCompletion { call.cancel() }

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = runCatching { response.body.string() }.getOrDefault("")
                        throw httpError(response.code, text)
                    }
                    val source = response.body.source()
                    // 有些中转站会忽略 stream:true 直接回整段 JSON，这里兜一下
                    val contentType = response.body.contentType()
                    val isEventStream = contentType?.subtype?.contains("event-stream", true) == true
                    if (!isEventStream) {
                        val content = parseDelta(response.body.string())
                        if (content.isNullOrEmpty()) {
                            throw AppError.BackendError(message = "模型返回的内容为空")
                        }
                        onDelta(content)
                        return@withContext content
                    }
                    val answer = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        // SSE：空行是事件分隔，`:` 开头是注释/心跳，`event:`/`id:` 用不到
                        if (line.isEmpty() || line.startsWith(":")) continue
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val delta = parseDelta(payload) ?: continue
                        if (delta.isEmpty()) continue
                        answer.append(delta)
                        onDelta(delta)
                    }
                    if (answer.isEmpty()) {
                        throw AppError.BackendError(message = "模型没有返回任何内容，换个模型或稍后再试")
                    }
                    answer.toString()
                }
            } catch (e: IOException) {
                // 用户点了「停止」时 OkHttp 会以 IOException 结束，这不是错误
                if (call.isCanceled()) throw CancellationException("已停止生成")
                throw mapIoError(e)
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

    private fun endpoint(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw AppError.InvalidInput("请先在设置里填写对话服务地址")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
