package io.savant.assey.data.parser

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * MinerU 官方接口的 Retrofit 声明。
 *
 * 只描述「一次 HTTP 往返」，不做任何业务编排：
 *  - 精准解析：走 `api/v4` 下的接口，需在请求头带 `Authorization: Bearer <token>`
 *  - 轻量解析：走 `api/v1/agent` 下的接口，免鉴权（IP 限频）
 *  - 上传 / 下载：预签名绝对地址，不带鉴权头
 *
 * 鉴权头由 [MineruApi] 的拦截器按路径自动补上，接口签名因此保持干净。
 * 文档：https://mineru.net/apiManage/docs
 */
interface MineruService {

    // ---------------- 精准解析（需 Token） ----------------

    /** 申请文件上传链接：返回 batch_id 与预签名 PUT 地址。 */
    @POST("api/v4/file-urls/batch")
    suspend fun createUploadBatch(
        @Body body: UploadBatchRequest
    ): MineruResponse<UploadBatchData>

    /** 提交远程链接解析任务。 */
    @POST("api/v4/extract/task/batch")
    suspend fun createUrlBatch(
        @Body body: UrlBatchRequest
    ): MineruResponse<BatchData>

    /** 查询批次结果（轮询）。 */
    @GET("api/v4/extract-results/batch/{batchId}")
    suspend fun batchResult(
        @Path("batchId") batchId: String
    ): MineruResponse<BatchResultData>

    // ---------------- 轻量解析（免 Token） ----------------

    /** 本地文件轻量解析：返回 task_id 与预签名上传地址。 */
    @POST("api/v1/agent/parse/file")
    suspend fun agentParseFile(
        @Body body: LiteFileRequest
    ): MineruResponse<LiteSubmitData>

    /** 远程链接轻量解析。 */
    @POST("api/v1/agent/parse/url")
    suspend fun agentParseUrl(
        @Body body: LiteUrlRequest
    ): MineruResponse<LiteSubmitData>

    /** 查询轻量解析结果（轮询）。 */
    @GET("api/v1/agent/parse/{taskId}")
    suspend fun agentResult(
        @Path("taskId") taskId: String
    ): MineruResponse<LiteResultData>

    // ---------------- 传输（预签名地址） ----------------

    /**
     * 预签名 PUT 上传：@Url 传绝对地址，因此不带 Authorization，也不设置 Content-Type。
     * 返回 Response 是为了检查状态码；正常响应体为空。
     */
    @PUT
    suspend fun putFile(
        @Url url: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    /** 流式下载结果包 / Markdown 文本。 */
    @Streaming
    @GET
    suspend fun getStream(
        @Url url: String
    ): Response<ResponseBody>
}
