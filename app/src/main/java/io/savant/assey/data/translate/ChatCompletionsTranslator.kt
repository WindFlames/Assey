package io.savant.assey.data.translate

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.savant.assey.core.AppError
import io.savant.assey.core.Http
import io.savant.assey.core.mapIoError
import io.savant.assey.domain.Translator
import io.savant.assey.md.MarkdownTranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 兼容翻译器的公共实现，直接走 OkHttp。
 *
 * 这里刻意不用 openai-java SDK：它为了覆盖全部接口，带进来 37000 多个模型/Builder 类
 * （APK 里 dex 的 84%），而本应用只用一次 `POST /chat/completions`。
 * 子类只决定「专有参数」与 id，翻译/分块/保护逻辑完全复用。
 *
 * Index-Translate 的额外约束：
 *  - baseUrl 指向 https://index-translate.bilibili.com/v1
 *  - apiKey 为空时仍发 `Bearer EMPTY`（服务端不校验但不能缺头）
 *  - User-Agent 必须显式设置，服务端会拒绝默认 UA
 *  - chat_template_kwargs 通过 [extraBody] 原样并入请求体
 */
abstract class ChatCompletionsTranslator(
    private val http: Http,
    protected val baseUrl: String,
    protected val model: String,
    protected val apiKey: String
) : Translator {

    /**
     * 后端专有请求字段，用数据类 / Map 描述，由 Gson 序列化后按顶层 key 并入请求体。
     * 例如 Index-Translate 的 chat_template_kwargs。
     */
    protected open val extraBody: Any? = null

    protected open val temperature: Double = 0.0
    protected open val maxTokens: Int = 4096

    /**
     * 复用全局连接池 / 调度器，只针对翻译放宽读超时（长文本单块可能较慢）。
     * 只 newBuilder 出一个派生 client，不新建连接池。
     */
    private val client = http.client.newBuilder()
        .readTimeout(TRANSLATE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val engine by lazy {
        MarkdownTranslationEngine(
            chat = { prompt -> completeOnce(prompt) }
        )
    }

    override suspend fun translate(
        markdown: String,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String = engine.translate(markdown, target, onProgress)

    protected open suspend fun completeOnce(prompt: String): String = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint())
            .post(buildBody(prompt).toString().toRequestBody(JSON))
            .header("User-Agent", USER_AGENT)
            // 部分端点（vLLM / Ollama）不校验 Key，但缺 Authorization 头有的网关会直接 401
            .header("Authorization", "Bearer ${apiKey.ifBlank { "EMPTY" }}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw serviceError(response.code, raw)
                val text = extractContent(raw)
                if (text.isBlank()) {
                    throw AppError.BackendError(message = "翻译服务返回了空内容")
                }
                text
            }
        } catch (e: AppError) {
            throw e
        } catch (e: IOException) {
            throw mapIoError(e)
        }
    }

    private fun buildBody(prompt: String): JsonObject {
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            })
        }
        return JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature)
            // 保留 max_tokens，兼容 Index-Translate / vLLM 等端点
            addProperty("max_tokens", maxTokens)
            add("messages", messages)
            // 专有字段按顶层 key 逐个并入；非对象形态直接跳过，避免打乱请求体结构
            extraBody?.let { extra ->
                val tree = GSON.toJsonTree(extra)
                if (tree.isJsonObject) {
                    tree.asJsonObject.entrySet().forEach { (key, value) -> add(key, value) }
                }
            }
        }
    }

    /** 取 `choices[0].message.content`；服务端把错误塞在 body 里时优先报那条。 */
    private fun extractContent(raw: String): String {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: throw AppError.BackendError(
                message = "翻译服务返回了无法解析的内容：${raw.take(120).replace("\n", " ")}"
            )
        root.getAsJsonObject("error")?.let { error ->
            val message = error.get("message")?.takeIf { !it.isJsonNull }?.asString
                ?: "翻译服务返回错误"
            throw AppError.BackendError(message = message)
        }
        return root.getAsJsonArray("choices")
            ?.firstOrNull()
            ?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            .orEmpty()
    }

    private fun serviceError(code: Int, body: String): AppError = when (code) {
        401, 403 -> AppError.AuthError("翻译服务鉴权失败（HTTP $code），请检查 API Key")
        413 -> AppError.FileTooLarge("翻译请求过大，请缩短文档后重试")
        429 -> AppError.RateLimited()
        else -> AppError.BackendError(
            code = code.toString(),
            message = "翻译服务返回错误（HTTP $code）${body.take(160).replace("\n", " ")}"
        )
    }

    private fun endpoint(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw AppError.InvalidInput("请先在设置里填写翻译服务地址")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    companion object {
        const val USER_AGENT = "Assey/1.0 (Android; personal use)"

        /** 单块翻译可能较慢，读超时放宽到 10 分钟。 */
        private const val TRANSLATE_READ_TIMEOUT_SECONDS = 600L

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GSON = Gson()
    }
}
