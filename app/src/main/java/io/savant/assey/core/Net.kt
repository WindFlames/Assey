package io.savant.assey.core

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 共享的 OkHttp 客户端。
 *
 * 具体请求由三处发起：MinerU 走 Retrofit（`data/parser/MineruApi`）、
 * 翻译与对话直接构造 OkHttp 请求（`data/translate/ChatCompletionsTranslator`、
 * `data/chat/ChatApi`），都复用这里的连接池。
 * 这里只负责连接参数与「IOException → 领域错误」的归一化。
 */
class Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // 大文件上传可能持续很久，不能有写超时
        .writeTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

fun httpError(code: Int, body: String, retryAfter: String? = null): AppError = when (code) {
    401, 403 -> AppError.AuthError()
    413 -> AppError.FileTooLarge()
    429 -> AppError.RateLimited(retryAfter?.trim()?.toLongOrNull() ?: 10L)
    else -> AppError.BackendError(
        code = code.toString(),
        message = "服务返回错误（HTTP $code）${body.take(160).replace("\n", " ")}"
    )
}

internal fun mapIoError(e: IOException): AppError {
    val text = e.message.orEmpty()
    return when {
        text.contains("timeout", ignoreCase = true) ->
            AppError.NetworkError("网络超时，请检查网络后重试")
        text.contains("Unable to resolve host", ignoreCase = true) ->
            AppError.NetworkError("无法连接服务器，请检查网络或稍后重试")
        text.contains("interrupted", ignoreCase = true) ->
            AppError.NetworkError("传输被中断")
        else -> AppError.NetworkError("网络连接失败：${text.ifBlank { e.javaClass.simpleName }}")
    }
}
