package io.savant.assey.data.translate

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.savant.assey.core.AppError
import io.savant.assey.core.net.ApiClient
import io.savant.assey.core.net.Auth
import io.savant.assey.core.net.BackendProfile
import io.savant.assey.core.net.Timeouts
import io.savant.assey.domain.Translator
import io.savant.assey.md.MarkdownTranslationEngine

/**
 * OpenAI Chat Completions 兼容翻译器的公共实现。
 *
 * 请求只做一次 `POST /chat/completions`，所以不走 openai-java SDK——它为覆盖全部接口
 * 带进来 37000 多个模型/Builder 类（APK 里 dex 的 84%）。传输细节交给 [ApiClient]，
 * 这里只描述「这个后端长什么样」和「怎么从响应里取文本」。
 *
 * Index-Translate 的额外约束：
 *  - baseUrl 指向 https://index-translate.bilibili.com/v1
 *  - apiKey 为空时仍发 `Bearer EMPTY`（服务端不校验但不能缺头）
 *  - User-Agent 必须显式设置，服务端会拒绝默认 UA
 *  - chat_template_kwargs 通过 [extraBody] 原样并入请求体
 */
abstract class ChatCompletionsTranslator(
    private val api: ApiClient,
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

    private val profile = BackendProfile(
        id = "translate",
        displayName = "翻译服务",
        baseUrl = baseUrl,
        auth = Auth.Bearer({ apiKey.ifBlank { "EMPTY" } }),
        headers = mapOf("User-Agent" to USER_AGENT),
        // 单块翻译可能较慢
        timeouts = Timeouts(readSeconds = TRANSLATE_READ_TIMEOUT_SECONDS)
    )

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

    protected open suspend fun completeOnce(prompt: String): String {
        val raw = api.postJson(profile, CHAT_PATH, buildBody(prompt))
        val text = extractContent(raw)
        if (text.isBlank()) {
            throw AppError.BackendError(message = "翻译服务返回了空内容")
        }
        return text
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

    companion object {
        const val USER_AGENT = "Assey/1.0 (Android; personal use)"

        /** 单块翻译可能较慢，读超时放宽到 10 分钟。 */
        private const val TRANSLATE_READ_TIMEOUT_SECONDS = 600L

        private const val CHAT_PATH = "chat/completions"

        private val GSON = Gson()
    }
}
