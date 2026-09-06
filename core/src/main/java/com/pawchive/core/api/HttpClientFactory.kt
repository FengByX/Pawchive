package com.pawchive.core.api

import com.pawchive.core.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * OkHttpClient 工厂（ARCH-009：HttpClientFactory 职责）。
 *
 * 统一构建不同类型 API 的客户端，各自使用独立可配置的超时与拦截器组合：
 * - [createApiClient]：公开 / 认证 API 主客户端（内存缓存 + CF 403 兜底 + 凭据注入 + 脱敏日志）；
 * - [createLoginClient]：登录客户端（不自动跟随重定向，登录结果由 302 判断）；
 * - [createImageClient]：图片 / 本地下载轻量客户端（无需过盾重试与内存缓存）。
 */
class HttpClientFactory {

    /**
     * 各客户端超时配置（毫秒）。
     * 默认：连接 15s / 读写 30s / 总 60s。
     */
    data class Timeouts(
        val connect: Long = 15_000L,
        val read: Long = 30_000L,
        val write: Long = 30_000L,
        val call: Long = 60_000L
    )

    /**
     * 构建主 API 客户端。可通过 [extraInterceptors] 附加会话注入等自定义拦截器。
     */
    fun createApiClient(
        extraInterceptors: List<Interceptor> = emptyList(),
        timeouts: Timeouts = Timeouts()
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeouts.connect, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.read, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.write, TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.call, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(ApiMemoryCache.intercept())
            .addInterceptor(ClearanceRetryInterceptor.intercept())
            .addInterceptor(ClearanceInterceptor.intercept())
            .addInterceptor(buildSanitizedLogger())
        extraInterceptors.forEach { builder.addInterceptor(it) }
        return builder.build()
    }

    /**
     * 构建登录客户端：不自动跟随重定向。
     */
    fun createLoginClient(): OkHttpClient {
        return createApiClient().newBuilder()
            .followRedirects(false)
            .build()
    }

    /**
     * 构建大文件下载专用客户端（okdownload / 直连流式保存）。
     *
     * 与 [createApiClient] 的关键差异，均与"不能中断长连接"有关：
     * - **不设置 callTimeout**：OkHttp 的 callTimeout 涵盖"读取响应体"的全过程，
     *   而下载是单个 Call 流式读取整个文件。沿用 API 客户端的 60s 总超时会让任何
     *   60 秒内传不完的文件被 OkHttp 看门狗取消（IOException: timeout）——
     *   这正是大附件下载必失败、小图片却正常的原因。
     * - **不挂 ApiMemoryCache**：该拦截器只对 JSON 响应生效，对二进制下载无意义，
     *   徒增一次拦截开销。
     * - readTimeout 放宽到 60s：弱网下单个 read 可能长时间无数据返回。
     */
    fun createDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(ClearanceRetryInterceptor.intercept())
            .addInterceptor(ClearanceInterceptor.intercept())
            .addInterceptor(buildSanitizedLogger())
            .build()
    }

    /**
     * 构建轻量客户端（图片 / 本地文件等无需过盾重试的场景）：
     * 仅基础超时与日志，不注入 CF 拦截器与内存缓存。
     */
    fun createImageClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .addInterceptor(logger)
            .build()
    }

    /**
     * 日志级别：debug 用 HEADERS（不打印 body 避免泄漏登录响应/Cookie/帖子内容），
     * release 用 NONE。敏感头（Authorization/Cookie/Set-Cookie）始终脱敏（P1）。
     */
    private fun buildSanitizedLogger(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            val sanitized = sanitizeLogMessage(message)
            if (sanitized != null) okhttp3.internal.platform.Platform.get().log(sanitized)
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
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
}
