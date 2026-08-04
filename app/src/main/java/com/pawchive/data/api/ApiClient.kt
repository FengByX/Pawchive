package com.pawchive.data.api

import com.pawchive.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val API_BASE_URL = "https://pawchive.pw/api/v1/"
    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    // authApi 实例缓存，key 为对应的 sessionCookie
    @Volatile
    private var cachedAuthApi: PawchiveApi? = null
    @Volatile
    private var cachedAuthCookie: String? = null

    // ── 内存缓存（应用运行期间有效，退出后自动清除）──
    private const val CACHE_MAX_AGE_MILLIS = 5 * 60 * 1000L // 5 分钟
    private const val CACHE_MAX_ENTRIES = 200 // 容量上限，防止缓存无限增长（补充③）
    private data class CacheEntry(val timestamp: Long, val body: ByteArray, val contentType: String?)
    private val apiMemoryCache = ConcurrentHashMap<String, CacheEntry>()

    // 当前登录会话的 hash（用于缓存命名空间隔离，避免明文 cookie 驻留内存键 / P2）
    @Volatile
    private var currentSessionHash: String? = null

    // 过盾等待超时：与 CloudflareManager 的挑战超时对齐，避免 OkHttp 线程被无限阻塞（P1）
    private const val CLEARANCE_WAIT_TIMEOUT_MS = 32_000L

    // 用于构造“过盾失败”占位响应（避免依赖已关闭的响应体 / P1）
    private val EMPTY_RESPONSE_BODY = ByteArray(0).toResponseBody("text/plain".toMediaType())

    /**
     * 内存缓存拦截器：缓存 GET 请求的 JSON 响应，避免短时间内重复请求。
     * 缓存有效期 5 分钟，应用退出后随进程销毁自动清除。
     * 只缓存 JSON 响应，不缓存图片等大文件。
     * 下拉刷新可通过 Header "Cache-Control: no-cache" 跳过缓存。
     */
    /**
     * 内存缓存拦截器：缓存 GET 请求的 JSON 响应，避免短时间内重复请求。
     * 缓存有效期 5 分钟，应用退出后随进程销毁自动清除。
     * 只缓存 JSON 响应，不缓存图片等大文件。
     * 下拉刷新可通过 Header "Cache-Control: no-cache" 跳过缓存。
     * 缓存键包含账号命名空间，杜绝跨用户缓存复用（P0）。
     */
    private val memoryCacheInterceptor = Interceptor { chain ->
        val request = chain.request()

        // 只缓存 GET 请求
        if (request.method != "GET") {
            return@Interceptor chain.proceed(request)
        }

        // 下拉刷新等场景通过 no-cache Header 跳过缓存
        if (request.header("Cache-Control")?.contains("no-cache") == true) {
            return@Interceptor chain.proceed(request)
        }

        // 缓存键加入账号维度：用 session hash 替代明文 cookie，避免凭据驻留内存键（P2）
        val namespace = currentSessionHash?.let { "u:$it" } ?: "public"
        val key = "$namespace|${request.url}"

        val now = System.currentTimeMillis()

        // 检查缓存
        val cached = apiMemoryCache[key]
        if (cached != null && now - cached.timestamp < CACHE_MAX_AGE_MILLIS) {
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK (from memory cache)")
                .body(cached.body.toResponseBody(cached.contentType?.toMediaType()))
                .build()
        }

        // 发起请求
        val response = chain.proceed(request)

        // 缓存成功的 JSON 响应
        if (response.isSuccessful) {
            val contentType = response.header("Content-Type") ?: response.body?.contentType()?.toString()
            if (contentType?.contains("json") == true) {
                val bodyBytes = response.body?.bytes()
                if (bodyBytes != null) {
                    putCache(key, CacheEntry(now, bodyBytes, contentType))
                    // body 已被消费，需重新构建 Response
                    return@Interceptor response.newBuilder()
                        .body(bodyBytes.toResponseBody(contentType.toMediaType()))
                        .build()
                }
            }
        }

        response
    }

    /**
     * 写入内存缓存，并回收过期条目、淘汰最旧条目以维持容量上限（补充③）。
     */
    private fun putCache(key: String, entry: CacheEntry) {
        val now = System.currentTimeMillis()
        // 回收过期条目
        apiMemoryCache.entries
            .filter { now - it.value.timestamp >= CACHE_MAX_AGE_MILLIS }
            .forEach { apiMemoryCache.remove(it.key) }
        // 超出容量上限则淘汰最旧的一条
        if (apiMemoryCache.size >= CACHE_MAX_ENTRIES) {
            apiMemoryCache.minByOrNull { it.value.timestamp }?.key?.let { apiMemoryCache.remove(it) }
        }
        apiMemoryCache[key] = entry
    }

    /**
     * 注入 Cloudflare 通行凭据的拦截器：
     * 仅对 pawchive.pw 主域的请求附加 cf_clearance Cookie 和 User-Agent。
     * 对 img.pawchive.pw 等 CDN 子域不注入 Referer/Cookie，避免触发防盗链。
     */
    private val cloudflareInterceptor = Interceptor { chain ->
        val original = chain.request()
        val host = original.url.host

        // 仅对 pawchive.pw 主域注入 CF 凭据；子域（如 img.pawchive.pw）不注入
        val isMainDomain = host == "pawchive.pw" || host.endsWith(".pawchive.pw")

        if (isMainDomain) {
            val builder = original.newBuilder()
            CloudflareManager.currentUserAgent()?.let { ua ->
                builder.header("User-Agent", ua)
            }
            builder.header("Referer", LOGIN_BASE_URL)

            val cfCookie = CloudflareManager.currentCookie()
            if (!cfCookie.isNullOrEmpty()) {
                val existing = original.header("Cookie")
                val merged = if (existing.isNullOrEmpty()) cfCookie else "$existing; $cfCookie"
                builder.header("Cookie", merged)
            }
            chain.proceed(builder.build())
        } else {
            // 非主域请求（如 img.pawchive.pw 的图片），只注入 UA（有助于过盾），
            // 不注入 Referer 和 Cookie（避免触发防盗链或服务器拒绝）
            val builder = original.newBuilder()
            CloudflareManager.currentUserAgent()?.let { ua ->
                builder.header("User-Agent", ua)
            }
            chain.proceed(builder.build())
        }
    }

    /**
     * Cloudflare 透明重试拦截器：
     * 若响应为 403，强制刷新 cf_clearance 并重试一次。
     * 关键修复：
     *   1. 添加重试限制，防止 ensureClearance 失败时陷入无限循环。
     *   2. 过盾失败时返回原始 403 响应（而非抛异常），让上层（如 Coil）
     *      能正常将其作为加载失败处理，而不是崩溃。
     */
    /**
     * Cloudflare 透明重试拦截器：
     * 若响应为 403，强制刷新 cf_clearance 并重试一次。
     * 本拦截器必须注册在 cloudflareInterceptor 之前（见 buildOkHttpClient），
     * 这样重试请求才能重新经过 cloudflareInterceptor 注入新 Cookie（修复自愈失效 / 补充①）。
     */
    private val cloudflareRetryInterceptor = Interceptor { chain ->
        val request = chain.request()

        // 已重试过一次，直接放行，避免无限循环
        if (request.header("X-CF-Retry") == "1") {
            return@Interceptor chain.proceed(request)
        }

        val response = chain.proceed(request)

        if (response.code != 403) {
            return@Interceptor response
        }

        // 关闭原始 403 响应释放连接（其 body 已无用）
        response.close()

        // CloudflareManager.ensureClearance 内部已实现单飞（inFlight CompletableDeferred），
        // 并发 403 会复用同一过盾任务，不会启动多个 WebView。
        // 这里仅阻塞当前 OkHttp 线程等待结果，带超时避免无限阻塞（P1）。
        val success = runBlocking {
            withTimeoutOrNull(CLEARANCE_WAIT_TIMEOUT_MS) {
                runCatching { CloudflareManager.ensureClearance(forceRefresh = true) }.getOrDefault(false)
            } ?: false
        }

        if (success) {
            // 标记已重试，重建请求后重新走完整链（cloudflareInterceptor 会注入新 Cookie）
            val rebuiltRequest = request.newBuilder()
                .header("X-CF-Retry", "1")
                .build()
            chain.proceed(rebuiltRequest)
        } else {
            // 过盾失败：返回新的 403 响应（不再依赖已关闭的原始响应），
            // 让上层（Coil / Retrofit）按加载失败处理
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Cloudflare clearance failed")
                .body(EMPTY_RESPONSE_BODY)
                .build()
        }
    }

    /**
     * 暴露给外部（Coil 图片加载、下载逻辑等）使用的 OkHttpClient，
     * 已注入 Cloudflare 凭据与重试逻辑。
     *
     * 注意：img.pawchive.pw 并非完全公开的 CDN，部分缩略图请求也会
     * 被 Cloudflare 拦截返回 403。因此图片加载也必须使用此客户端，
     * 让 cloudflareRetryInterceptor 自动刷新 cf_clearance 并重试。
     */
    val sharedOkHttpClient: OkHttpClient by lazy { buildOkHttpClient() }

    /**
     * 轻量级 OkHttpClient（无 Cloudflare 拦截器），
     * 仅用于确定不需要过盾的场景（如本地文件下载等）。
     */
    val imageOkHttpClient: OkHttpClient by lazy {
        val logger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .addInterceptor(logger)
            .build()
    }

    private fun buildOkHttpClient(): OkHttpClient {
        // 日志级别：debug 用 HEADERS（不打印 body 避免泄漏登录响应/Cookie/帖子内容），
        // release 用 NONE。敏感头（Authorization/Cookie/Set-Cookie）始终脱敏（P1）。
        val logger = HttpLoggingInterceptor { message ->
            val sanitized = sanitizeLogMessage(message)
            if (sanitized != null) okhttp3.internal.platform.Platform.get().log(sanitized)
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(memoryCacheInterceptor)
            .addInterceptor(cloudflareRetryInterceptor)
            .addInterceptor(cloudflareInterceptor)
            .addInterceptor(logger)
            .build()
    }

    /**
     * 日志脱敏：拦截 Authorization / Cookie / Set-Cookie 头，仅输出头名与掩码值（P1）。
     * 返回 null 表示丢弃该行（如 BODY 级别的二进制内容）。
     */
    private fun sanitizeLogMessage(message: String): String? {
        val sensitiveHeaders = listOf("Authorization", "Cookie", "Set-Cookie")
        for (header in sensitiveHeaders) {
            if (message.startsWith("$header:", ignoreCase = true)) {
                val parts = message.split(":", limit = 2)
                val value = if (parts.size == 2) parts[1].trim() else ""
                val masked = if (value.isEmpty()) "<empty>" else "<redacted ${value.length} chars>"
                return "$header: $masked"
            }
        }
        return message
    }

    private val okHttpClient: OkHttpClient by lazy { buildOkHttpClient() }

    val publicApi: PawchiveApi by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveApi::class.java)
    }

    fun authApi(sessionCookie: String): PawchiveApi {
        // 复用同一 sessionCookie 对应的实例，避免每次调用都重建 OkHttp/Retrofit
        cachedAuthApi?.let { cached ->
            if (cachedAuthCookie == sessionCookie) return cached
        }

        // 设定当前会话 hash，使内存缓存归入该账号命名空间（P2：用 hash 替代明文 cookie）
        currentSessionHash = hashSession(sessionCookie)

        val cookieInterceptor = Interceptor { chain ->
            val original = chain.request()
            // 与已有 Cookie（如 Cloudflare 注入的 cf_clearance）合并，避免出现重复的 Cookie 头
            val existing = original.header("Cookie")
            val merged = if (existing.isNullOrEmpty()) {
                "session=$sessionCookie"
            } else {
                "$existing; session=$sessionCookie"
            }
            val request = original.newBuilder()
                .header("Cookie", merged)
                .build()
            chain.proceed(request)
        }

        val client = okHttpClient.newBuilder()
            .addInterceptor(cookieInterceptor)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveApi::class.java)

        cachedAuthCookie = sessionCookie
        cachedAuthApi = api
        return api
    }

    /**
     * 清除内存缓存并重置认证实例与当前会话。
     * 登出或切换账号时调用，避免旧账号数据残留（P0 / P1）。
     */
    fun clearMemoryCache() {
        apiMemoryCache.clear()
        cachedAuthApi = null
        cachedAuthCookie = null
        currentSessionHash = null
    }

    /**
     * 将 session cookie 转为 SHA-256 hash 前 16 字符，用于缓存键命名空间（P2）。
     * 不存储原始 cookie，仅存 hash，降低内存泄漏风险。
     */
    private fun hashSession(cookie: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(cookie.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            // 极端情况降级为 hashCode，仍不含明文
            cookie.hashCode().toString(16)
        }
    }

    val loginApi: PawchiveLoginApi by lazy {
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .build()

        Retrofit.Builder()
            .baseUrl(LOGIN_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PawchiveLoginApi::class.java)
    }
}
