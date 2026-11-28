package io.savant.assey.data.parser

import io.savant.assey.core.AppError

/**
 * MinerU 专有的错误码翻译与请求体工具。
 * 所有后端错误都在这里收敛成 [AppError]，Parser / UI 不再接触 HTTP 细节。
 */

internal const val PRECISION_PAGE_LIMIT = 200
internal const val LITE_PAGE_LIMIT = 20

/** MinerU 业务错误码 → 领域错误。 */
internal fun mineruError(code: String?, msg: String?): AppError = when (code) {
    "A0202", "A0211", "A0201" ->
        AppError.AuthError("MinerU Token 无效或已过期（$code），请到设置页重新填写")

    // 精准解析
    "-60005" -> AppError.FileTooLarge("MinerU 拒绝：文件超过后端大小限制（$code）")
    "-60006" -> AppError.PageLimitExceeded(
        "MinerU 拒绝：页数超过 $PRECISION_PAGE_LIMIT 页（$code），请填写页码范围"
    )
    "-60007" -> AppError.BackendError(code, "MinerU 模型服务暂不可用，请稍后重试")
    "-60008" -> AppError.NetworkError("MinerU 读取文件超时，请检查文件或链接后重试")
    "-60009" -> AppError.RateLimited(15L, "MinerU 队列已满，请稍后重试")

    // 轻量解析（Agent）
    "-30001" -> AppError.FileTooLarge("MinerU 轻量解析拒绝：文件超过 10MB（$code）")
    "-30002" -> AppError.BackendError(code, "MinerU 轻量解析不支持该文件类型")
    "-30003" -> AppError.PageLimitExceeded(
        "MinerU 轻量解析拒绝：页数超过 $LITE_PAGE_LIMIT 页（$code），请填写页码范围"
    )
    "-30004" -> AppError.InvalidInput("MinerU 拒绝了请求参数（$code），请检查页码范围等设置")

    else -> AppError.BackendError(
        code,
        msg?.takeIf { it.isNotBlank() } ?: "解析服务返回错误（code=$code）"
    )
}
