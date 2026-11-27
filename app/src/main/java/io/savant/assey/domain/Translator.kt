package io.savant.assey.domain

/**
 * 可替换的翻译后端契约。实现内部可自由使用任何 HTTP 协议细节，
 * UI 不感知 chat_template_kwargs / model 等专有参数。
 */
interface Translator {
    val id: String

    /**
     * 翻译整篇 Markdown，保留结构与不可翻译片段。
     * @param onProgress done 为已完成的块数，total 为总块数。
     */
    suspend fun translate(
        markdown: String,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String
}
