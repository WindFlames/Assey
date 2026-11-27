package io.savant.assey.data.translate

import io.savant.assey.core.Http

/**
 * 任意 OpenAI 兼容端点（OpenAI / DeepSeek / 本地 vLLM / Ollama ...）。
 * 只依赖 Base URL + 模型名 + Key，无需改 UI。
 */
class OpenAiCompatibleTranslator(
    http: Http,
    baseUrl: String,
    model: String,
    apiKey: String
) : ChatCompletionsTranslator(http, baseUrl, model, apiKey) {

    override val id: String = "openai-compatible"
}
