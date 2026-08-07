package com.pawchive.core.api

import okhttp3.Interceptor

/**
 * Cloudflare 凭据注入拦截器（ARCH-009：从 ApiClient 拆出）。
 *
 * 仅对 pawchive.pw 主域附加 cf_clearance Cookie / User-Agent / Referer；
 * 对 img.pawchive.pw 等 CDN 子域只注入 UA（有助于过盾），
 * 不注入 Referer 和 Cookie（避免触发防盗链或服务器拒绝）。
 */
object ClearanceInterceptor {

    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    fun intercept(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val host = original.url.host

        val isMainDomain = host == "pawchive.pw" || host.endsWith(".pawchive.pw")

        if (isMainDomain) {
            val builder = original.newBuilder()
            CloudflareManager.currentUserAgent()?.let { ua ->
                builder.header("User-Agent", ua)
            }
            builder.header("Referer", LOGIN_BASE_URL)

            val cfCookie = CloudflareManager.currentCookie()
                ?.let { CloudflareManager.stripSessionTokens(it) }
                ?.takeIf { it.isNotBlank() }
            if (!cfCookie.isNullOrEmpty()) {
                val existing = original.header("Cookie")
                val merged = if (existing.isNullOrEmpty()) cfCookie else "$existing; $cfCookie"
                builder.header("Cookie", merged)
            }
            chain.proceed(builder.build())
        } else {
            // 非主域请求（如 img.pawchive.pw 的图片），只注入 UA
            val builder = original.newBuilder()
            CloudflareManager.currentUserAgent()?.let { ua ->
                builder.header("User-Agent", ua)
            }
            chain.proceed(builder.build())
        }
    }
}
