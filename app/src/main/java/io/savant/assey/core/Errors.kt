package io.savant.assey.core

/**
 * 统一领域错误。所有后端实现必须把 HTTP / 业务错误码转换成这里的类型，
 * UI 只认这些类型并展示中文提示。
 */
sealed class AppError(message: String) : Exception(message) {

    class AuthError(
        message: String = "Token 无效或已过期，请到设置页重新填写"
    ) : AppError(message)

    class FileTooLarge(
        message: String = "文件超过 20MB 限制，已拒绝上传"
    ) : AppError(message)

    class PageLimitExceeded(
        message: String = "页数超过后端限制，请填写页码范围后重试"
    ) : AppError(message)

    class RateLimited(
        val retryAfterSeconds: Long = 5,
        message: String = "请求过于频繁，请稍后重试"
    ) : AppError(message)

    class NetworkError(
        message: String = "网络异常，请检查网络后重试"
    ) : AppError(message)

    class BackendError(
        val code: String? = null,
        message: String
    ) : AppError(message)

    class InvalidInput(message: String) : AppError(message)

    class StorageError(message: String) : AppError(message)

    class TranslationError(message: String) : AppError(message)

    class Cancelled : AppError("任务已取消")
}

/** 持久化用的错误类别标记。 */
fun Throwable.errorKind(): String = when (this) {
    is AppError.AuthError -> "AUTH"
    is AppError.FileTooLarge -> "FILE_TOO_LARGE"
    is AppError.PageLimitExceeded -> "PAGE_LIMIT"
    is AppError.RateLimited -> "RATE_LIMITED"
    is AppError.NetworkError -> "NETWORK"
    is AppError.BackendError -> "BACKEND"
    is AppError.InvalidInput -> "INVALID_INPUT"
    is AppError.StorageError -> "STORAGE"
    is AppError.TranslationError -> "TRANSLATION"
    is AppError.Cancelled -> "CANCELLED"
    else -> "UNKNOWN"
}

fun Throwable.userMessage(): String = when (this) {
    is AppError -> message ?: "未知错误"
    is OutOfMemoryError -> "内存不足，文件可能过大"
    else -> "发生未知错误：${message ?: javaClass.simpleName}"
}
