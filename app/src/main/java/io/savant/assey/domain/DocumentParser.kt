package io.savant.assey.domain

/**
 * 可替换的文档解析后端契约。UI / ViewModel / 任务流水线只依赖本接口。
 *
 * 新增后端：实现本接口 + 在 ParserFactory 注册即可，不需要改 UI。
 */
interface DocumentParser {
    /** 稳定标识，会写入任务元数据，用于重启后找回同一个后端。 */
    val id: String

    /** 是否产出图片资源目录（用于 UI 提示与导出）。 */
    val supportsImages: Boolean

    suspend fun parse(
        request: ParseRequest,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult

    /**
     * 进程被杀后继续轮询未完成后端任务。
     * 默认不支持；支持的后端应覆写，且必须保证 resumeToken 是后端受理任务后才产生的。
     */
    suspend fun resume(
        resumeToken: String,
        dataId: String,
        options: ParseOptions,
        onProgress: (ParseProgress) -> Unit
    ): ParseResult {
        throw io.savant.assey.core.AppError.BackendError(
            message = "该解析后端不支持任务恢复，请重新提交"
        )
    }
}
