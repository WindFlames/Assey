package io.savant.assey.data.parser

import com.google.gson.annotations.SerializedName
import io.savant.assey.domain.MineruModel

/**
 * MinerU 接口的传输模型（DTO）与内部快照。
 *
 * 约定：
 *  - `@SerializedName` 与官方文档字段严格一致，避免命名策略带来的歧义
 *  - 可选字段一律可空并提供默认值；Gson 默认不序列化 null，所以不填的参数不会出现在请求体里
 *  - DTO 只在 [MineruService] / [MineruApi] 之间流转，各 Parser 只接触下面「内部快照」
 */

// ---------------- 通用响应外框 ----------------

/**
 * 官方统一响应：`{ code, msg, trace_id, data }`。
 * `code` 用 String 承载，兼容后端返回数字或字符串两种写法（`0` 视为成功）。
 */
data class MineruResponse<T>(
    @SerializedName("code") val code: String? = null,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("trace_id") val traceId: String? = null,
    @SerializedName("data") val data: T? = null
) {
    val isSuccess: Boolean get() = code == null || code == "0"
}

// ---------------- 精准解析：请求 ----------------

data class UploadBatchRequest(
    @SerializedName("enable_formula") val enableFormula: Boolean = true,
    @SerializedName("enable_table") val enableTable: Boolean = true,
    @SerializedName("language") val language: String = "ch",
    /** 取值为 [io.savant.assey.domain.MineruModel.value]，DTO 只负责原样传输。 */
    @SerializedName("model_version") val modelVersion: String = MineruModel.DEFAULT.value,
    @SerializedName("files") val files: List<UploadFileEntry>
)

data class UploadFileEntry(
    @SerializedName("name") val name: String,
    @SerializedName("data_id") val dataId: String,
    @SerializedName("is_ocr") val isOcr: Boolean = false,
    @SerializedName("page_ranges") val pageRanges: String? = null
)

data class UrlBatchRequest(
    @SerializedName("enable_formula") val enableFormula: Boolean = true,
    @SerializedName("enable_table") val enableTable: Boolean = true,
    @SerializedName("language") val language: String = "ch",
    /** 取值为 [io.savant.assey.domain.MineruModel.value]，DTO 只负责原样传输。 */
    @SerializedName("model_version") val modelVersion: String = MineruModel.DEFAULT.value,
    @SerializedName("files") val files: List<UrlFileEntry>
)

data class UrlFileEntry(
    @SerializedName("url") val url: String,
    @SerializedName("name") val name: String,
    @SerializedName("data_id") val dataId: String,
    @SerializedName("is_ocr") val isOcr: Boolean = false,
    @SerializedName("page_ranges") val pageRanges: String? = null
)

// ---------------- 精准解析：响应 ----------------

data class UploadBatchData(
    @SerializedName("batch_id") val batchId: String? = null,
    /** 官方是字符串数组；这里用 Any 兼容对象数组 `[{ "file_url": ... }]`。 */
    @SerializedName("file_urls") val fileUrls: List<Any?>? = null
)

data class BatchData(
    @SerializedName("batch_id") val batchId: String? = null
)

data class BatchResultData(
    @SerializedName("batch_id") val batchId: String? = null,
    @SerializedName("extract_result") val extractResult: List<ExtractResultItem>? = null
)

data class ExtractResultItem(
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("data_id") val dataId: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("err_msg") val errMsg: String? = null,
    @SerializedName("full_zip_url") val fullZipUrl: String? = null,
    @SerializedName("extract_progress") val extractProgress: ExtractProgress? = null
)

data class ExtractProgress(
    @SerializedName("extracted_pages") val extractedPages: Int = 0,
    @SerializedName("total_pages") val totalPages: Int = 0,
    @SerializedName("start_time") val startTime: String? = null
)

// ---------------- 轻量解析：请求 ----------------

data class LiteFileRequest(
    @SerializedName("file_name") val fileName: String,
    @SerializedName("language") val language: String = "ch",
    @SerializedName("enable_table") val enableTable: Boolean = true,
    @SerializedName("is_ocr") val isOcr: Boolean = false,
    @SerializedName("enable_formula") val enableFormula: Boolean = true,
    /** 与精准解析不同：轻量接口字段名是单数 page_range，且只支持 `1-10` 或 `5`。 */
    @SerializedName("page_range") val pageRange: String? = null
)

data class LiteUrlRequest(
    @SerializedName("url") val url: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("language") val language: String = "ch",
    @SerializedName("enable_table") val enableTable: Boolean = true,
    @SerializedName("is_ocr") val isOcr: Boolean = false,
    @SerializedName("enable_formula") val enableFormula: Boolean = true,
    @SerializedName("page_range") val pageRange: String? = null
)

// ---------------- 轻量解析：响应 ----------------

data class LiteSubmitData(
    @SerializedName("task_id") val taskId: String? = null,
    @SerializedName("file_url") val fileUrl: String? = null,
    /** 兼容旧版字段名。 */
    @SerializedName("upload_url") val uploadUrl: String? = null
)

data class LiteResultData(
    @SerializedName("task_id") val taskId: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("markdown_url") val markdownUrl: String? = null,
    @SerializedName("err_msg") val errMsg: String? = null,
    @SerializedName("err_code") val errCode: Int? = null
)

// ---------------- 内部快照（Parser 只依赖这些） ----------------

/** 任务受理凭证：batch_id + 预签名上传地址（URL 提交时为空）。 */
data class BatchTicket(
    val batchId: String,
    val uploadUrl: String?
)

/** 轻量任务受理凭证：task_id + 预签名上传地址（URL 提交时为空）。 */
data class LiteTicket(
    val taskId: String,
    val uploadUrl: String?
)

/** 批次中单个文件的状态快照。 */
data class MineruResultItem(
    val dataId: String?,
    val state: String,
    val errMsg: String?,
    val zipUrl: String?,
    val pagesDone: Int,
    val pagesTotal: Int
)

/** 轻量解析的状态快照。 */
data class LiteTaskState(
    val state: String,
    val markdownUrl: String?,
    val errMsg: String?,
    val errCode: String?
)

/** Token 探测结果：不抛业务异常，交由调用方判定。 */
data class BatchProbe(
    val httpCode: Int,
    val bizCode: String?,
    val message: String?
)
