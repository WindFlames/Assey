package io.savant.assey.data.translate

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.savant.assey.core.AppError
import io.savant.assey.core.Http
import io.savant.assey.domain.Translator
import io.savant.assey.md.MarkdownTranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 兼容翻译器的公共实现，底层使用官方 openai-java SDK。
 * 子类只决定「专有参数」与 id，翻译/分块/保护逻辑完全复用。
 *
 * Index-Translate 仍通过 SDK 调用：
 *  - baseUrl 指向 https://index-translate.bilibili.com/v1
 *  - apiKey 为空时使用 EMPTY
 *  - User-Agent 用 OkHttp 拦截器覆盖（服务端会拒绝默认 UA）
 *  - chat_template_kwargs 通过 additional body property 原样带入
 */
abstract class ChatCompletionsTranslator(
    private val http: Http,
    protected val baseUrl: String,
    protected val model: String,
    protected val apiKey: String
) : Translator {

    /**
     * 后端专有请求字段，用数据类 / Map 描述，由 Jackson 序列化后原样并入请求体。
     * 例如 Index-Translate 的 chat_template_kwargs。
     */
    protected open val extraBody: Any? = null

    protected open val temperature: Double = 0.0
    protected open val maxTokens: Int = 4096

    private val client: OpenAIClient by lazy {
        // 复用全局连接池 / 调度器，只针对翻译放宽读超时（长文本单块可能较慢）。
        val okHttp = http.client.newBuilder()
            .readTimeout(TRANSLATE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        OpenAIOkHttpClient.builder()
            .apiKey(apiKey.ifBlank { "EMPTY" })
            .baseUrl(baseUrl.trim().trimEnd('/'))
            // 显式设置 UA 后 SDK 不再追加默认 UA（Index-Translate 会拒绝默认 UA）
            .replaceHeaders("User-Agent", USER_AGENT)
            .okHttpClient(okHttp)
            .build()
    }

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
        try {
            val params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(prompt)
                .temperature(temperature)
                .apply {
                    // 保留 max_tokens，兼容 Index-Translate / vLLM 等端点
                    putAdditionalBodyProperty("max_tokens", JsonValue.from(maxTokens))
                    // 专有字段（如 chat_template_kwargs）按 key 逐个并入请求体；
                    // asObject() 只接受对象形态，非对象值直接跳过，避免打乱请求体结构。
                    extraBody?.let { extra ->
                        JsonValue.from(extra).asObject().orElse(emptyMap()).forEach { (key, value) ->
                            putAdditionalBodyProperty(key, value)
                        }
                    }
                }
                .build()

            val completion = client.chat().completions().create(params)
            val text = completion.choices().firstOrNull()
                ?.message()
                ?.content()
                ?.orElse("")
                .orEmpty()
            if (text.isBlank()) {
                throw AppError.BackendError(message = "翻译服务返回了空内容")
            }
            text
        } catch (e: AppError) {
            throw e
        } catch (e: OpenAIServiceException) {
            throw mapServiceError(e)
        } catch (e: OpenAIIoException) {
            throw AppError.NetworkError("无法连接翻译服务：${e.message ?: "网络异常"}")
        } catch (e: Exception) {
            throw AppError.BackendError(
                message = "翻译服务调用失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun mapServiceError(e: OpenAIServiceException): AppError {
        val code = e.statusCode()
        return when (code) {
            401, 403 -> AppError.AuthError("翻译服务鉴权失败（HTTP $code），请检查 API Key")
            413 -> AppError.FileTooLarge("翻译请求过大，请缩短文档后重试")
            429 -> AppError.RateLimited()
            else -> AppError.BackendError(
                code = code.toString(),
                message = "翻译服务返回错误（HTTP $code）${e.message.orEmpty().take(160)}"
            )
        }
    }

    companion object {
        const val USER_AGENT = "Assey/1.0 (Android; personal use)"

        /** 单块翻译可能较慢，读超时放宽到 10 分钟。 */
        private const val TRANSLATE_READ_TIMEOUT_SECONDS = 600L
    }
}
