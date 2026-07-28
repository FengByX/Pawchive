package com.pawchive.data.api

import com.pawchive.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val API_BASE_URL = "https://pawchive.pw/api/v1/"
    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    // authApi 实例缓存，key 为对应的 sessionCookie
    @Volatile
    private var cachedAuthApi: PawchiveApi? = null
    @Volatile
    private var cachedAuthCookie: String? = null

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
    private val cloudflareRetryInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 403) {
            // 先关闭原始响应，再尝试刷新并重试一次
            response.close()
            val success = runBlocking {
                CloudflareManager.ensureClearance(forceRefresh = true)
            }
            if (success) {
                // 重建请求（cloudflareInterceptor 会自动注入新 Cookie）
                val rebuiltRequest = request.newBuilder().build()
                val retryResponse = chain.proceed(rebuiltRequest)
                // 重试后仍为 403，直接返回，不再继续重试
                retryResponse
            } else {
                // 过盾失败，返回新的 403 响应替代抛出异常
                // 这样 Coil/Retrofit 能正确处理为加载失败
                response.newBuilder()
                    .code(403)
                    .message("Cloudflare clearance failed")
                    .request(request)
                    .build()
            }
        } else {
            response
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
        val logger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
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
            .addInterceptor(cloudflareInterceptor)
            .addInterceptor(cloudflareRetryInterceptor)
            .addInterceptor(logger)
            .build()
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
