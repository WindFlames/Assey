package io.savant.assey.domain

import java.io.File

/** 解析后端选择。 */
enum class ParserKind(val label: String) {
    AUTO("自动"),
    PRECISION("精准（MinerU，带图片）"),
    LITE("轻量（免 Token，无图片）")
}

enum class SourceKind { LOCAL_FILE, REMOTE_URL }

/**
 * MinerU 解析模型版本，对应请求体的 `model_version`。
 *  - [VLM]：多模态大模型，公式/表格/版式还原更好，官方默认
 *  - [PIPELINE]：传统管线，兼容性最好，输出结构最稳定
 *  - [HTML]：面向网页/HTML 类文档
 */
enum class MineruModel(val value: String, val label: String) {
    VLM("vlm", "VLM"),
    PIPELINE("pipeline", "Pipeline"),
    HTML("MinerU-HTML", "HTML");

    companion object {
        val DEFAULT = VLM

        /** 容错解析：本地旧数据、接口返回的未知取值统一回落到默认模型。 */
        fun fromValue(raw: String?): MineruModel {
            val key = raw?.trim().orEmpty()
            if (key.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.value.equals(key, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * 结果包下载方式。
 *  - BUILT_IN：App 内用 OkHttp/Retrofit 流式下载，进度在任务页与通知栏
 *  - EXTERNAL：交给系统 DownloadManager，通知栏可见，可切后台甚至退出 App
 */
enum class DownloadMode(val label: String) {
    BUILT_IN("内置下载"),
    EXTERNAL("系统下载")
}

enum class TaskStatus(val label: String) {
    DRAFT("待处理"),
    COPYING("复制文件"),
    UPLOADING("上传中"),
    PARSING("解析中"),
    DOWNLOADING("下载结果"),
    EXTRACTING("解压中"),
    TRANSLATING("翻译中"),
    DONE("已完成"),
    FAILED("失败"),
    CANCELED("已取消");

    val isRunning: Boolean
        get() = this == DRAFT || this == COPYING || this == UPLOADING || this == PARSING ||
            this == DOWNLOADING || this == EXTRACTING || this == TRANSLATING

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == CANCELED
}

enum class TranslateStatus(val label: String) {
    NONE("未翻译"),
    RUNNING("翻译中"),
    DONE("已翻译"),
    FAILED("翻译失败")
}

/** 解析阶段（领域层，不暴露任何后端专有概念）。 */
enum class ParseStage {
    PREPARING,
    REQUESTING,
    UPLOADING,
    QUEUED,
    PARSING,
    DOWNLOADING,
    EXTRACTING,
    FINISHED
}

data class ParseOptions(
    /** 解析模型版本 */
    val model: MineruModel = MineruModel.DEFAULT,
    /** 空串表示全部页码，格式如 1-10 或 2,4-6 */
    val pageRanges: String = "",
    val isOcr: Boolean = false,
    val enableFormula: Boolean = true,
    val enableTable: Boolean = true,
    val language: String = "ch"
)

data class ParseRequest(
    val localFile: File?,
    val url: String?,
    val options: ParseOptions,
    val fileName: String,
    val dataId: String
) {
    init {
        require((localFile == null) != (url == null)) { "localFile 与 url 必须二选一" }
    }
}

data class ParseProgress(
    val stage: ParseStage,
    val message: String = "",
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /**
     * 可用于进程重启后续跑的后端任务标识（MinerU 的 batch_id / task_id）。
     * 只有后端已经真正受理任务后才会非空。
     */
    val resumeToken: String? = null
)

data class ParseResult(
    val markdown: String,
    /** 图片等资源目录；无图后端为 null。Markdown 内的相对路径以此目录为根。 */
    val assetsDir: File?,
    val backendId: String
)

/** 持久化在本地的一条任务记录。 */
data class TaskRecord(
    val id: String,
    val title: String,
    val sourceKind: SourceKind,
    val sourceUri: String? = null,
    val remoteUrl: String? = null,
    val localPath: String? = null,
    val sizeBytes: Long = 0L,
    val backendId: String? = null,
    val backendName: String? = null,
    val options: ParseOptions = ParseOptions(),
    val status: TaskStatus = TaskStatus.DRAFT,
    val stageLabel: String = "",
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val resumeToken: String? = null,
    val errorKind: String? = null,
    val errorMessage: String? = null,
    val markdownPath: String? = null,
    val assetsPath: String? = null,
    val translatedPath: String? = null,
    val translateStatus: TranslateStatus = TranslateStatus.NONE,
    val supportsImages: Boolean = true,
    val warning: String? = null,
    val autoTranslate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean get() = status.isRunning

    /** 0f..1f，无法计算时为 null。 */
    val progressFraction: Float?
        get() = when {
            status == TaskStatus.UPLOADING && bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            status == TaskStatus.COPYING && bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            status == TaskStatus.DOWNLOADING && bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            pagesTotal > 0 -> (pagesDone.toFloat() / pagesTotal).coerceIn(0f, 1f)
            else -> null
        }

    val displayStatus: String
        get() = when (status) {
            TaskStatus.TRANSLATING -> TranslateStatus.RUNNING.label
            else -> if (stageLabel.isNotBlank() && status.isRunning) stageLabel else status.label
        }
}

data class Settings(
    val mineruToken: String = "",
    val parserKind: ParserKind = ParserKind.AUTO,
    val downloadMode: DownloadMode = DownloadMode.BUILT_IN,
    val defaultModel: MineruModel = MineruModel.DEFAULT,
    val defaultLanguage: String = "ch",
    val isOcr: Boolean = false,
    val enableFormula: Boolean = true,
    val enableTable: Boolean = true,
    val autoTranslate: Boolean = true,
    val translatorBaseUrl: String = DEFAULT_TRANSLATOR_BASE,
    val translatorModel: String = DEFAULT_TRANSLATOR_MODEL,
    val translatorApiKey: String = "",
    /** 对话服务地址；清空则回退到翻译服务的 baseUrl。 */
    val chatBaseUrl: String = DEFAULT_CHAT_BASE,
    /** 对话模型名；留空则用 [DEFAULT_CHAT_MODEL]。 */
    val chatModel: String = DEFAULT_CHAT_MODEL,
    /** 对话服务的 Key；留空则复用翻译服务的 Key。 */
    val chatApiKey: String = "",
    val chatSystemPrompt: String = DEFAULT_CHAT_PROMPT
) {
    companion object {
        const val DEFAULT_TRANSLATOR_BASE = "https://index-translate.bilibili.com/v1"
        const val DEFAULT_TRANSLATOR_MODEL = "Index-Translate-35B-A3B"
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val LITE_MAX_FILE_BYTES = 10L * 1024 * 1024
        const val DEFAULT_CHAT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_CHAT_MODEL = "gpt-4o-mini"
        const val DEFAULT_CHAT_PROMPT =
            "你是一个严谨、简洁的中文助手。回答用 Markdown，代码块标注语言；不确定的地方直说不确定。"
        val TRANSLATOR_MODELS = listOf(
            "Index-Translate-35B-A3B",
            "Index-Translate-9B",
            "Index-Translate-2B"
        )
        val CHAT_MODELS = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "deepseek-chat",
            "qwen-plus"
        )
    }
}
