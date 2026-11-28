package io.savant.assey.core.net

import io.savant.assey.core.AppError
import io.savant.assey.core.Http
import io.savant.assey.core.mapIoError
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 统一的 HTTP 执行器：所有后端共用同一套请求构造、鉴权、超时、进度与错误映射。
 *
 * 提供四种形态，覆盖本应用的全部调用：
 *  - [postJson]      一次 JSON 往返（翻译）
 *  - [stream]        SSE 长连接逐行读取（对话）
 *  - [download]     绝对地址下载到文件 / 边下边处理（MinerU 结果包）
 *  - [upload]       绝对地址预签名 PUT（MinerU 文件上传）
 *
 * 二进制传输刻意不走 Retrofit：`@Streaming` 拿到的还是 `ResponseBody`，
 * 进度、完整性校验、取消都得自己写一遍，不如直接用 OkHttp。JSON 接口仍可交给
 * Retrofit（见 [client]），那部分注解和 DTO 更省事。
 */
class ApiClient(private val http: Http) {

    /**
     * 按 profile 派生一个 client：只追加 header / 鉴权拦截器和超时，
     * 连接池、dispatcher、已有拦截器都从共享的 [Http.client] 继承。
     *
     * 每次调用返回新实例是安全的——OkHttp 的派生 client 共享底层资源。
     */
    fun client(profile: BackendProfile): OkHttpClient =
        http.client.newBuilder()
            .addInterceptor(ProfileInterceptor(profile))
            .apply {
                profile.timeouts.connectSeconds?.let { connectTimeout(it, TimeUnit.SECONDS) }
                profile.timeouts.readSeconds?.let { readTimeout(it, TimeUnit.SECONDS) }
                profile.timeouts.writeSeconds?.let { writeTimeout(it, TimeUnit.SECONDS) }
            }
            .build()

    // ---------------- JSON ----------------

    /** 发一个 JSON 请求，返回响应体原文。非 2xx 抛 [AppError]。 */
    suspend fun postJson(
        profile: BackendProfile,
        path: String,
        body: JsonObject
    ): String {
        val request = Request.Builder()
            .url(profile.endpoint(path))
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request, client(profile), profile) { response -> response.body.string() }
    }

    // ---------------- 流式 ----------------

    /**
     * 发起流式请求，把响应交给 [block] 逐行读。
     * 上层自己判断 Content-Type 是否真是 SSE（有些中转会忽略 stream:true 回整段 JSON）。
     */
    suspend fun <T> stream(
        profile: BackendProfile,
        path: String,
        body: JsonObject,
        block: (ApiStream) -> T
    ): T {
        val request = Request.Builder()
            .url(profile.endpoint(path))
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "text/event-stream")
            .build()
        return execute(request, client(profile), profile) { response ->
            val payload = response.body
            block(
                ApiStream(
                    contentType = payload.contentType()?.toString(),
                    contentLength = payload.contentLength(),
                    source = payload.source()
                )
            )
        }
    }

    // ---------------- 下载 ----------------

    /** 下载到文件，带进度；服务端给了 Content-Length 时校验完整性。 */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        downloadStream(url, onProgress) { payload ->
            dest.parentFile?.mkdirs()
            dest.outputStream().buffered(BUFFER_BYTES).use { output ->
                payload.input.copyTo(output, BUFFER_BYTES)
            }
            if (payload.contentLength > 0 && dest.length() != payload.contentLength) {
                throw AppError.NetworkError("下载不完整，请重试")
            }
        }
    }

    /**
     * 边下边处理：字节流直接交给 [block]，App 内不落临时文件。
     * 用于「下载结果包 → 立刻解压」，省掉一次写盘再读回。
     */
    suspend fun <T> downloadStream(
        url: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        block: (Download) -> T
    ): T {
        // 预签名地址，不带任何鉴权头：直接用共享 client，不派生 profile
        val request = Request.Builder().url(url).get().build()
        return execute(request, http.client, null) { response ->
            val payload = response.body
            val total = payload.contentLength()
            var done = 0L
            payload.byteStream().use { raw ->
                val counting = object : FilterInputStream(raw) {
                    private fun advance(read: Int) {
                        if (read <= 0) return
                        done += read
                        onProgress(done, if (total > 0) total else -1L)
                    }

                    override fun read(): Int = super.read().also { advance(it) }

                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { advance(it) }
                }
                block(Download(total, counting))
            }
        }
    }

    // ---------------- 上传 ----------------

    /** 预签名 PUT：不带鉴权头、不设置 Content-Type，带上传进度。 */
    suspend fun upload(
        url: String,
        file: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val request = Request.Builder()
            .url(url)
            .put(ProgressRequestBody(file, onProgress))
            .build()
        execute(
            request = request,
            client = http.client,
            errorMapper = { response ->
                val text = runCatching { response.body.string() }.getOrDefault("")
                AppError.BackendError(
                    code = response.code.toString(),
                    message = "文件上传失败（HTTP ${response.code}），请重新提交任务" +
                        text.take(80).replace("\n", " ")
                )
            }
        ) { Unit }
    }

    // ---------------- 公共执行 ----------------

    /**
     * 统一的执行壳：注册取消、判状态码、归一化异常。
     * 协程取消时顺带 `call.cancel()`，不会留下悬挂的 socket。
     */
    private suspend fun <T> execute(
        request: Request,
        client: OkHttpClient,
        profile: BackendProfile? = null,
        /** 特定调用方的错误文案；为空时用按 profile 生成的通用文案。 */
        errorMapper: ((Response) -> AppError)? = null,
        block: (Response) -> T
    ): T = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw errorMapper?.invoke(response) ?: failure(response, profile)
                }
                block(response)
            }
        } catch (e: AppError) {
            throw e
        } catch (e: IOException) {
            // 用户点了「停止」时 OkHttp 会以 IOException 结束，这不是网络错误
            if (call.isCanceled()) throw CancellationException("已取消")
            throw mapIoError(e)
        }
    }

    /** 错误文案带上后端语境（「翻译服务鉴权失败」比「Token 无效」更好定位）。 */
    private fun failure(response: Response, profile: BackendProfile?): AppError {
        val code = response.code
        val text = runCatching { response.body.string() }.getOrDefault("")
        val name = profile?.displayName
        return when (code) {
            401, 403 -> AppError.AuthError(
                if (name != null) "${name}鉴权失败（HTTP $code），请检查 API Key"
                else "Token 无效或已过期，请到设置页重新填写"
            )
            413 -> AppError.FileTooLarge(
                if (name != null) "${name}拒绝了过大的请求，请缩短内容后重试"
                else "文件超过 20MB 限制，已拒绝上传"
            )
            429 -> AppError.RateLimited(
                retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull() ?: 10L,
                message = if (name != null) "${name}请求过于频繁，请稍后重试" else "请求过于频繁，请稍后重试"
            )
            else -> AppError.BackendError(
                code = code.toString(),
                message = "${name ?: "服务"}返回错误（HTTP $code）${text.take(160).replace("\n", " ")}"
            )
        }
    }

    private companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** SSE / 流式响应的可读视图。 */
class ApiStream(
    val contentType: String?,
    val contentLength: Long,
    val source: BufferedSource
)

/** 下载流与声明的长度（未知时为 -1）。 */
class Download(
    val contentLength: Long,
    val input: InputStream
)

/** 把 profile 的固定头与鉴权头注入每个请求。路径判断用真实请求路径，预签名地址自然不会命中。 */
private class ProfileInterceptor(private val profile: BackendProfile) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        profile.headers.forEach { (name, value) -> builder.header(name, value) }
        profile.auth.headerFor(request.url.encodedPath)?.let { builder.header("Authorization", it) }
        return chain.proceed(builder.build())
    }
}

/** 带进度的请求体，用于文件上传。 */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: (done: Long, total: Long) -> Unit
) : RequestBody() {

    // MinerU 的预签名 PUT 不能带 Content-Type，默认 null
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
    }
}
