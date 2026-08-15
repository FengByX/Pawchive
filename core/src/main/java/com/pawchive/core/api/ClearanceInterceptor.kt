package com.pawchive.core.api

import okhttp3.Interceptor

/**
 * Cloudflare 凭据注入拦截器（ARCH-009：从 ApiClient 拆出）。
 *
 * - 对 pawchive.pw 主域：附加 cf_clearance Cookie / User-Agent / Referer
 * - 对 img.pawchive.pw 等 CDN 子域：只注入 UA（有助于过盾），
 *   不注入 Referer 和 Cookie（避免触发防盗链或服务器拒绝，避免泄露敏感凭据）
 * - 其它域名：不注入任何头
 */
object ClearanceInterceptor {

    private const val LOGIN_BASE_URL = "https://pawchive.pw/"

    fun intercept(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val host = original.url.host

        // 主域：pawchive.pw - 注入完整凭据
        // CDN 子域：*.pawchive.pw - 仅注入 UA
        val isMainDomain = host == "pawchive.pw"
        val isCdnSubdomain = host.endsWith(".pawchive.pw")

        if (isMainDomain || isCdnSubdomain) {
            val builder = original.newBuilder()
            CloudflareManager.currentUserAgent()?.let { ua ->
                builder.header("User-Agent", ua)
            }

            if (isMainDomain) {
                // 仅主域注入 Referer 和 Cookie
                builder.header("Referer", LOGIN_BASE_URL)

                val cfCookie = CloudflareManager.currentCookie()
                    ?.let { CloudflareManager.stripSessionTokens(it) }
                    ?.takeIf { it.isNotBlank() }
                if (!cfCookie.isNullOrEmpty()) {
                    val existing = original.header("Cookie")
                    val merged = if (existing.isNullOrEmpty()) cfCookie else "$existing; $cfCookie"
                    builder.header("Cookie", merged)
                }
            }
            // CDN 子域只注入 UA，不注入 Referer/Cookie
            chain.proceed(builder.build())
        } else {
            chain.proceed(original)
        }
    }
}
