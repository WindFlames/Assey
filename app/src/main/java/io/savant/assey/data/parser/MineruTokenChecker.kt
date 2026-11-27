package io.savant.assey.data.parser

import io.savant.assey.core.AppError
import java.util.UUID

/**
 * Token 有效性检测。
 * 用一次「查询不存在的 batch」请求来验证鉴权（走 Retrofit 的同一个 batchResult 接口）：
 *  - A0202 / A0211 / A0201 / 401 / 403 → Token 无效
 *  - 其他业务码（例如 batch 不存在）或 404 → 说明 Token 已被服务端接受
 */
class MineruTokenChecker(
    private val api: MineruApi
) {
    suspend fun check(rawToken: String): Result<Unit> {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            return Result.failure(AppError.AuthError("Token 为空，请先填写"))
        }
        val probe = UUID.randomUUID().toString()
        return try {
            val result = api.withToken(token).probeBatch(probe)
            val bizCode = result.bizCode
            when {
                result.httpCode == 401 || result.httpCode == 403 ->
                    Result.failure(
                        AppError.AuthError("Token 无效或已过期（HTTP ${result.httpCode}）")
                    )

                bizCode != null && bizCode in AUTH_ERROR_CODES -> Result.failure(
                    AppError.AuthError(
                        "Token 无效或已过期（$bizCode）：" + result.message.orEmpty()
                    )
                )

                result.httpCode == 404 || result.httpCode in 200..299 -> Result.success(Unit)

                else -> Result.failure(
                    AppError.BackendError(
                        code = result.bizCode ?: result.httpCode.toString(),
                        message = result.message
                            ?: "MinerU 返回异常（HTTP ${result.httpCode}），请稍后重试"
                    )
                )
            }
        } catch (e: AppError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AppError.NetworkError("无法连接 MinerU：${e.message ?: "未知错误"}"))
        }
    }

    private companion object {
        val AUTH_ERROR_CODES = setOf("A0202", "A0211", "A0201")
    }
}
