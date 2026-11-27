package io.savant.assey.data.store

import android.content.Context
import io.savant.assey.domain.DownloadMode
import io.savant.assey.domain.MineruModel
import io.savant.assey.domain.ParserKind
import io.savant.assey.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

class SettingsStore(
    context: Context,
    private val secure: SecureStore
) {
    private val prefs = context.getSharedPreferences("assay_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun snapshot(): Settings = _state.value

    fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        persist(next)
        _state.value = next
    }

    private fun load(): Settings {
        val token = prefs.getString(KEY_TOKEN, null)?.let { secure.decrypt(it) }.orEmpty()
        val apiKey = prefs.getString(KEY_API_KEY, null)?.let { secure.decrypt(it) }.orEmpty()
        return Settings(
            mineruToken = token,
            parserKind = enumOrDefault(prefs.getString(KEY_PARSER, null), ParserKind.AUTO),
            downloadMode = enumOrDefault(prefs.getString(KEY_DOWNLOAD_MODE, null), DownloadMode.BUILT_IN),
            defaultModel = MineruModel.fromValue(prefs.getString(KEY_MODEL, null)),
            defaultLanguage = prefs.getString(KEY_LANGUAGE, "ch") ?: "ch",
            isOcr = prefs.getBoolean(KEY_OCR, false),
            enableFormula = prefs.getBoolean(KEY_FORMULA, true),
            enableTable = prefs.getBoolean(KEY_TABLE, true),
            autoTranslate = prefs.getBoolean(KEY_AUTO_TRANSLATE, true),
            translatorBaseUrl = prefs.getString(KEY_TRANSLATOR_BASE, Settings.DEFAULT_TRANSLATOR_BASE)
                ?: Settings.DEFAULT_TRANSLATOR_BASE,
            translatorModel = prefs.getString(KEY_TRANSLATOR_MODEL, Settings.DEFAULT_TRANSLATOR_MODEL)
                ?: Settings.DEFAULT_TRANSLATOR_MODEL,
            translatorApiKey = apiKey
        )
    }

    private fun persist(settings: Settings) {
        prefs.edit {
            putString(KEY_TOKEN, secure.encrypt(settings.mineruToken))
                putString(KEY_API_KEY, secure.encrypt(settings.translatorApiKey))
                putString(KEY_PARSER, settings.parserKind.name)
                putString(KEY_DOWNLOAD_MODE, settings.downloadMode.name)
                putString(KEY_MODEL, settings.defaultModel.value)
                putString(KEY_LANGUAGE, settings.defaultLanguage)
                putBoolean(KEY_OCR, settings.isOcr)
                putBoolean(KEY_FORMULA, settings.enableFormula)
                putBoolean(KEY_TABLE, settings.enableTable)
                putBoolean(KEY_AUTO_TRANSLATE, settings.autoTranslate)
                putString(KEY_TRANSLATOR_BASE, settings.translatorBaseUrl)
                putString(KEY_TRANSLATOR_MODEL, settings.translatorModel)
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    companion object {
        private const val KEY_TOKEN = "mineru_token_enc"
        private const val KEY_API_KEY = "translator_key_enc"
        private const val KEY_PARSER = "parser_kind"
        private const val KEY_DOWNLOAD_MODE = "download_mode"
        private const val KEY_MODEL = "default_model"
        private const val KEY_LANGUAGE = "default_language"
        private const val KEY_OCR = "is_ocr"
        private const val KEY_FORMULA = "enable_formula"
        private const val KEY_TABLE = "enable_table"
        private const val KEY_AUTO_TRANSLATE = "auto_translate"
        private const val KEY_TRANSLATOR_BASE = "translator_base"
        private const val KEY_TRANSLATOR_MODEL = "translator_model"

        /** 用户可能粘贴 "Bearer xxx"、带引号或首尾空白，统一清理。 */
        fun sanitizeToken(raw: String): String = raw
            .trim()
            .trim('"', '\'', '“', '”')
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
            .trim('"', '\'', '“', '”')
            .trim()
    }
}
