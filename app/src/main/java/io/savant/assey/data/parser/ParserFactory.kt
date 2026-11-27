package io.savant.assey.data.parser

import io.savant.assey.core.AppError
import io.savant.assey.data.download.ResultDownloader
import io.savant.assey.domain.DocumentParser
import io.savant.assey.domain.ParserKind
import io.savant.assey.domain.Settings
import java.io.File

/**
 * 解析后端工厂。UI 只传 ParserKind，不感知具体实现。
 * 「自动」：有 Token 走精准（20MB 内），无 Token 走轻量（10MB 内，提示无图片）。
 *
 * 两个后端共享同一个 [MineruApi]（Retrofit 实例 + 动态 Token 拦截器），
 * 下载方式由 [downloaderProvider] 按当前设置返回（内置 / 系统下载）。
 */
class ParserFactory(
    private val api: MineruApi,
    private val tokenProvider: () -> String,
    private val taskDirProvider: (String) -> File,
    private val downloaderProvider: () -> ResultDownloader
) {

    data class Selection(
        val parser: DocumentParser,
        val warning: String? = null
    )

    private val precision: DocumentParser by lazy {
        MineruPrecisionParser(api, tokenProvider, taskDirProvider, downloaderProvider)
    }
    private val lite: DocumentParser by lazy {
        MineruLiteParser(api, taskDirProvider, downloaderProvider)
    }

    fun select(kind: ParserKind, sizeBytes: Long, isUrl: Boolean): Selection {
        val hasToken = tokenProvider().isNotBlank()
        return when (kind) {
            ParserKind.PRECISION -> {
                if (!hasToken) {
                    throw AppError.AuthError("未配置 MinerU Token，无法使用精准解析")
                }
                Selection(precision)
            }

            ParserKind.LITE -> {
                if (!isUrl && sizeBytes > Settings.LITE_MAX_FILE_BYTES) {
                    throw AppError.FileTooLarge(
                        "轻量解析仅支持 10MB 以内文件，请配置 Token 使用精准解析"
                    )
                }
                Selection(lite, "轻量解析不返回图片，公式/表格可能不完整")
            }

            ParserKind.AUTO -> when {
                hasToken -> Selection(precision)
                !isUrl && sizeBytes > Settings.LITE_MAX_FILE_BYTES -> throw AppError.FileTooLarge(
                    "未配置 MinerU Token，轻量解析仅支持 10MB 以内文件"
                )
                else -> Selection(lite, "未配置 Token，已使用轻量解析（无图片）")
            }
        }
    }

    /** 重启恢复时按任务里记录的 backendId 找回同一个后端。 */
    fun byId(id: String?): DocumentParser? = when (id) {
        MineruPrecisionParser.ID -> precision
        MineruLiteParser.ID -> lite
        else -> null
    }

    fun nameOf(parser: DocumentParser): String = when (parser.id) {
        MineruPrecisionParser.ID -> "MinerU 精准解析"
        MineruLiteParser.ID -> "MinerU 轻量解析"
        else -> parser.id
    }
}
