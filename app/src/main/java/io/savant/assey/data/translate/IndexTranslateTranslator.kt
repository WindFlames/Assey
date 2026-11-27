package io.savant.assey.data.translate

import io.savant.assey.core.Http

/**
 * Index-Translate 默认实现（免 Token，OpenAI 兼容）。
 * 专有参数 chat_template_kwargs 只存在于本类，不会泄漏到领域层。
 */
class IndexTranslateTranslator(
    http: Http,
    baseUrl: String,
    model: String,
    apiKey: String
) : ChatCompletionsTranslator(http, baseUrl, model, apiKey) {

    override val id: String = ID

    override val extraBody: Any = IndexTranslateExtraBody(
        chat_template_kwargs = ChatTemplateKwargs(enable_thinking = false)
    )

    companion object {
        const val ID = "index-translate"
        const val BASE_URL = "https://index-translate.bilibili.com/v1"
        const val DEFAULT_MODEL = "Index-Translate-35B-A3B"
    }
}

/**
 * Index-Translate 的附加请求体。字段名直接对应 JSON，
 * 由 SDK 内部的 Jackson 序列化后并入请求。
 */
data class IndexTranslateExtraBody(
    val chat_template_kwargs: ChatTemplateKwargs
)

data class ChatTemplateKwargs(
    val enable_thinking: Boolean
)
