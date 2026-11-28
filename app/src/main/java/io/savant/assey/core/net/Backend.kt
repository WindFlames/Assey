package io.savant.assey.core.net

import io.savant.assey.core.AppError

/**
 * 一个后端服务的连接与鉴权描述。
 *
 * 应用要对接三类形态完全不同的服务，差异只有这几项：
 *  - MinerU：`Bearer` 只对 `/api/v4/` 加（预签名上传/下载地址带上就会被拒）
 *  - Index-Translate：必须自定义 `User-Agent`，Key 为空时仍要发 `Bearer EMPTY`
 *  - 任意 OpenAI 兼容端点：Bearer 可选（本地 vLLM / Ollama 不校验）
 *
 * 把这些差异收敛到 profile 后，上层只关心「往哪个路径发什么 JSON」。
 * 新增后端 = 声明一个 profile，不需要再写一套请求/错误处理。
 */
class BackendProfile(
    val id: String,
    val baseUrl: String,
    /** 错误提示里用的中文名，例如「翻译服务」。留空则用 [id]。 */
    val displayName: String = id,
    val auth: Auth = Auth.None,
    /** 固定附加的请求头，如 Index-Translate 的自定义 UA。 */
    val headers: Map<String, String> = emptyMap(),
    val timeouts: Timeouts = Timeouts()
) {

    /**
     * 拼接完整地址。`path` 为空时直接用 baseUrl；
     * baseUrl 已经是完整接口地址（用户把 `/chat/completions` 一起填了）时不重复追加。
     */
    fun endpoint(path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw AppError.InvalidInput("请先在设置里填写服务地址")
        if (path.isBlank()) return base
        val clean = path.trimStart('/')
        return if (base.endsWith("/$clean")) base else "$base/$clean"
    }
}

/** 请求鉴权方式。token 用 lambda 取，MinerU 的 Token 在设置里可以随时改。 */
sealed interface Auth {

    /** 针对某个请求路径算出要加的 `Authorization` 值；null 表示不加头。 */
    fun headerFor(path: String): String?

    /** 不加任何鉴权头。 */
    data object None : Auth {
        override fun headerFor(path: String): String? = null
    }

    /**
     * 加 `Authorization: Bearer <token>`。
     * @param pathPrefix 非空时只对以此开头的路径生效（MinerU 的预签名地址不能带鉴权）。
     * token 为空则不加头。
     */
    class Bearer(
        private val tokenProvider: () -> String,
        private val pathPrefix: String? = null
    ) : Auth {

        override fun headerFor(path: String): String? {
            if (pathPrefix != null && !path.startsWith(pathPrefix)) return null
            val token = tokenProvider().trim()
            return if (token.isEmpty()) null else "Bearer $token"
        }
    }
}

/**
 * 超时覆盖。null 表示沿用 [io.savant.assey.core.Http] 里的默认值。
 * 读超时传 0 表示「永不超时」，SSE 长连接用这个值。
 */
data class Timeouts(
    val connectSeconds: Long? = null,
    val readSeconds: Long? = null,
    val writeSeconds: Long? = null
)
