package io.savant.assey.data.translate

import io.savant.assey.core.Http
import io.savant.assey.domain.Settings
import io.savant.assey.domain.Translator

/**
 * 翻译后端工厂：
 *  - Base URL 指向 index-translate → IndexTranslateTranslator（带 chat_template_kwargs）
 *  - 其他任意 OpenAI 兼容端点 → OpenAiCompatibleTranslator
 * 新增翻译后端只需加实现类并在这里注册。
 */
class TranslatorFactory(
    private val http: Http,
    private val settingsProvider: () -> Settings
) {

    fun create(): Translator {
        val settings = settingsProvider()
        return create(
            baseUrl = settings.translatorBaseUrl,
            model = settings.translatorModel,
            apiKey = settings.translatorApiKey
        )
    }

    fun create(baseUrl: String, model: String, apiKey: String): Translator {
        val base = baseUrl.ifBlank { Settings.DEFAULT_TRANSLATOR_BASE }
        val effectiveModel = model.ifBlank { Settings.DEFAULT_TRANSLATOR_MODEL }
        return if (isIndexTranslate(base)) {
            IndexTranslateTranslator(http, base, effectiveModel, apiKey)
        } else {
            OpenAiCompatibleTranslator(http, base, effectiveModel, apiKey)
        }
    }

    private fun isIndexTranslate(baseUrl: String): Boolean =
        baseUrl.lowercase().contains("index-translate")
}
