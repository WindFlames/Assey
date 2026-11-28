package io.savant.assey.data.translate

import io.savant.assey.core.net.ApiClient

/**
 * 任意 OpenAI 兼容端点（OpenAI / DeepSeek / 本地 vLLM / Ollama ...）。
 * 只依赖 Base URL + 模型名 + Key，无需改 UI。
 */
class OpenAiCompatibleTranslator(
    api: ApiClient,
    baseUrl: String,
    model: String,
    apiKey: String
) : ChatCompletionsTranslator(api, baseUrl, model, apiKey) {

    override val id: String = "openai-compatible"
}
