package io.savant.assey.data.parser

import com.google.gson.JsonParser
import io.savant.assey.core.AppError
import io.savant.assey.core.httpError
import io.savant.assey.core.mapIoError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException

/**
 * MinerU 调用层：Retrofit 实例的持有者，负责
 *  1. 按路径自动补 `Authorization`（预签名上传地址与轻量接口不加）；
 *  2. 把 HTTP 状态码 / 业务 code / IOException 统一翻译成 [AppError]；
 *  3. 把 DTO 收敛成内部快照，并把上传、下载的进度回调暴露给上层。
 *
 * 轮询节奏与阶段提示留在各 Parser，这一层不做业务判断。
 */
class MineruApi(
    private val client: OkHttpClient,
    private val tokenProvider: () -> String = { "" },
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val service: MineruService = Retrofit.Builder()
        .baseUrl(normalize(baseUrl))
        .client(client.newBuilder().addInterceptor(AuthInterceptor(tokenProvider)).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MineruService::class.java)

    /** 用指定 Token 复制一份客户端，供设置页「测试 Token」使用。 */
    fun withToken(token: String): MineruApi = MineruApi(client, { token }, baseUrl)

    // ---------------- 精准解析 ----------------

    suspend fun createUploadBatch(body: UploadBatchRequest): BatchTicket {
        val data = await { service.createUploadBatch(body) }
        return BatchTicket(
            batchId = data.batchId.required("MinerU 未返回 batch_id"),
            uploadUrl = data.fileUrls?.firstNotNullOfOrNull { it.asUrl() }
        )
    }

    suspend fun createUrlBatch(body: UrlBatchRequest): BatchTicket {
        val data = await { service.createUrlBatch(body) }
        return BatchTicket(
            batchId = data.batchId.required("MinerU 未返回 batch_id"),
            uploadUrl = null
        )
    }

    suspend fun batchResult(batchId: String): List<MineruResultItem> {
        val data = await { service.batchResult(batchId) }
        return data.extractResult.orEmpty().map { item ->
            MineruResultItem(
                dataId = item.dataId,
                state = item.state?.takeIf { it.isNotBlank() } ?: STATE_PENDING,
                errMsg = item.errMsg?.takeIf { it.isNotBlank() },
                zipUrl = item.fullZipUrl?.takeIf { it.isNotBlank() },
                pagesDone = item.extractProgress?.extractedPages ?: 0,
                pagesTotal = item.extractProgress?.totalPages ?: 0
            )
        }
    }

    /**
     * 用「查询不存在的 batch」探测 Token 是否被服务端接受。
     * 这里不抛业务异常：鉴权失败与「batch 不存在」需要调用方区分。
     */
    suspend fun probeBatch(batchId: String): BatchProbe = try {
        val response = service.batchResult(batchId)
        BatchProbe(HTTP_OK, response.code, response.msg)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val biz = bizError(body)
        BatchProbe(e.code(), biz?.first, biz?.second)
    } catch (e: IOException) {
        throw mapIoError(e)
    }

    // ---------------- 轻量解析 ----------------

    suspend fun createLiteFileTask(body: LiteFileRequest): LiteTicket {
        val data = await { service.agentParseFile(body) }
        return LiteTicket(
            taskId = data.taskId.required("轻量解析未返回 task_id"),
            uploadUrl = (data.fileUrl ?: data.uploadUrl)?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun createLiteUrlTask(body: LiteUrlRequest): LiteTicket {
        val data = await { service.agentParseUrl(body) }
        return LiteTicket(
            taskId = data.taskId.required("轻量解析未返回 task_id"),
            uploadUrl = null
        )
    }

    suspend fun liteResult(taskId: String): LiteTaskState {
        val data = await { service.agentResult(taskId) }
        return LiteTaskState(
            state = data.state?.takeIf { it.isNotBlank() } ?: STATE_PENDING,
            markdownUrl = data.markdownUrl?.takeIf { it.isNotBlank() },
            errMsg = data.errMsg?.takeIf { it.isNotBlank() },
            errCode = data.errCode?.toString()
        )
    }

    // ---------------- 上传 / 下载 ----------------

    /** 预签名 PUT：不带鉴权头、不设置 Content-Type，带上传进度。 */
    suspend fun uploadFile(
        url: String,
        file: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val response = try {
            service.putFile(url, ProgressRequestBody(file, onProgress = onProgress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toAppError()
        }
        if (!response.isSuccessful) {
            val text = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
            throw AppError.BackendError(
                code = response.code().toString(),
                message = "文件上传失败（HTTP ${response.code()}），请重新提交任务" +
                    text.take(80).replace("\n", " ")
            )
        }
        response.body()?.close()
    }

    /** 流式下载到本地文件，带进度。 */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val response = openStream(url)
        val body = response.body() ?: throw AppError.NetworkError("下载结果为空")
        val total = body.contentLength()
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            var done = 0L
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, if (total > 0) total else -1L)
                    }
                }
            }
        }
        if (total > 0 && dest.length() != total) {
            throw AppError.NetworkError("下载不完整，请重试")
        }
    }

    private suspend fun openStream(url: String): retrofit2.Response<okhttp3.ResponseBody> {
        val response = try {
            service.getStream(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toAppError()
        }
        if (!response.isSuccessful) {
            val text = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
            throw httpError(response.code(), text, response.headers()["Retry-After"])
        }
        return response
    }

    // ---------------- 响应 → 领域 ----------------

    private suspend fun <T> await(block: suspend () -> MineruResponse<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toAppError()
        }
        if (!response.isSuccess) throw mineruError(response.code, response.msg)
        return response.data ?: throw AppError.BackendError(message = "解析服务未返回数据")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://mineru.net"
        const val STATE_PENDING = "pending"

        private const val HTTP_OK = 200

        private fun normalize(baseUrl: String): String =
            if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }
}

/** MinerU 要求所有业务接口都带 Bearer Token；预签名地址与轻量接口不能带。 */
private class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider().trim()
        val needsAuth = token.isNotEmpty() && request.url.encodedPath.startsWith(AUTH_PATH_PREFIX)
        if (!needsAuth) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }

    private companion object {
        const val AUTH_PATH_PREFIX = "/api/v4/"
    }
}

private fun String?.required(message: String): String =
    this ?: throw AppError.BackendError(message = message)

/** `file_urls` 兼容字符串数组与对象数组两种形态。 */
private fun Any?.asUrl(): String? = when (val value = this) {
    is String -> value.takeIf { it.isNotBlank() }
    is Map<*, *> -> ((value["file_url"] ?: value["url"]) as? String)?.takeIf { it.isNotBlank() }
    else -> null
}

private fun Throwable.toAppError(): AppError {
    return when (val error = this) {
        is AppError -> error
        is HttpException -> httpFailure(error)
        is IOException -> mapIoError(error)
        else -> AppError.NetworkError(
            "请求解析服务失败：${error.message ?: error.javaClass.simpleName}"
        )
    }
}

/** 优先按业务 code 判断（Token 失效、队列已满等都在这里），否则回落到 HTTP 状态码。 */
private fun httpFailure(e: HttpException): AppError {
    val raw = e.response()
    val body = runCatching { raw?.errorBody()?.string() }.getOrNull()
    val biz = bizError(body)
    if (biz != null) return mineruError(biz.first, biz.second)
    return httpError(e.code(), body.orEmpty(), raw?.headers()?.get("Retry-After"))
}

private fun bizError(body: String?): Pair<String, String?>? {
    if (body.isNullOrBlank()) return null
    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
    val code = json.get("code")?.takeIf { !it.isJsonNull }?.asString ?: return null
    if (code.isBlank() || code == "0") return null
    val msg = json.get("msg")?.takeIf { !it.isJsonNull }?.asString
    return code to msg
}
