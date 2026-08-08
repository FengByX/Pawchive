package com.pawchive.core.error

import android.content.Context
import com.pawchive.core.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 数据层统一错误类型（P2 BACKEND-007）。
 *
 * 设计目标：
 * - 替代散落在各处的 `Exception(message)` 与 `e.message` 字符串匹配；
 * - 保留错误类型信息（网络/认证/服务器/业务/未知），避免 UI 层再做脆弱的字符串判断；
 * - 统一映射到用户友好文案，所有 UI 层仅需调用 [toMessage]。
 *
 * 使用方式：
 * ```
 * val result: Result<T> = runCatching { ... }.recoverCatching { throw AppError.from(it) }
 * // 或在 ApiCallHandler / Repository 中直接返回 Result.failure(AppError.xxx)
 * ```
 */
sealed class AppError : RuntimeException() {

    /** 网络不可用：无法解析主机、连接被拒、SSL 握手失败等 */
    data class Network(
        val kind: Kind,
        override val cause: Throwable? = null
    ) : AppError() {
        enum class Kind { UNREACHABLE, SERVER_UNREACHABLE, TIMEOUT, SSL, RESET }
    }

    /** HTTP 4xx/5xx：403/404/500 等，携带状态码与服务器返回的消息 */
    data class Server(
        val code: Int,
        val serverMessage: String? = null,
        override val cause: Throwable? = null
    ) : AppError()

    /** 认证失败：未登录、session 过期、401 */
    data class Auth(
        val reason: Reason = Reason.SESSION_EXPIRED,
        override val cause: Throwable? = null
    ) : AppError() {
        enum class Reason { NOT_LOGGED_IN, SESSION_EXPIRED, INVALID_CREDENTIALS }
    }

    /** Cloudflare 挑战未通过 */
    data class CloudflareChallenge(
        override val cause: Throwable? = null
    ) : AppError()

    /** 业务校验失败：参数错误、用户名已存在等，message 直接面向用户 */
    data class Business(
        val userMessage: String,
        override val cause: Throwable? = null
    ) : AppError()

    /** 未知错误：兜底 */
    data class Unknown(
        override val cause: Throwable? = null
    ) : AppError()

    /**
     * 映射到用户友好的字符串文案。
     * 业务错误直接返回构造时传入的文案；其他类型按 [ErrorMessageHelper] 的既有资源映射。
     */
    fun toMessage(context: Context): String {
        return when (this) {
            is Network -> when (kind) {
                Network.Kind.UNREACHABLE -> context.getString(R.string.error_network_unreachable)
                Network.Kind.SERVER_UNREACHABLE -> context.getString(R.string.error_server_unreachable)
                Network.Kind.TIMEOUT -> context.getString(R.string.error_timeout)
                Network.Kind.SSL -> context.getString(R.string.error_ssl)
                Network.Kind.RESET -> context.getString(R.string.error_connection_reset)
            }
            is Server -> when (code) {
                401 -> context.getString(R.string.error_auth_expired)
                403 -> context.getString(R.string.error_forbidden)
                404 -> context.getString(R.string.error_not_found)
                in 500..599 -> context.getString(R.string.error_server_error)
                else -> context.getString(R.string.error_unknown)
            }
            is Auth -> when (reason) {
                Auth.Reason.NOT_LOGGED_IN -> context.getString(R.string.error_not_logged_in)
                Auth.Reason.SESSION_EXPIRED -> context.getString(R.string.error_auth_expired)
                Auth.Reason.INVALID_CREDENTIALS -> context.getString(R.string.error_auth)
            }
            is CloudflareChallenge -> context.getString(R.string.error_cloudflare)
            is Business -> userMessage
            is Unknown -> context.getString(R.string.error_unknown)
        }
    }

    companion object {
        /**
         * 从任意 Throwable 构造 [AppError]。
         * 优先识别常见网络异常类型，其余降级为 [Unknown] 并保留原始 cause。
         */
        fun from(throwable: Throwable): AppError {
            return when (throwable) {
                is AppError -> throwable
                is UnknownHostException -> Network(Network.Kind.UNREACHABLE, throwable)
                is ConnectException -> Network(Network.Kind.SERVER_UNREACHABLE, throwable)
                is SocketTimeoutException -> Network(Network.Kind.TIMEOUT, throwable)
                is SSLPeerUnverifiedException,
                is SSLException -> Network(Network.Kind.SSL, throwable)
                is IOException -> {
                    // 连接重置/断开归类为 RESET；其他 IOException 兜底为不可达
                    val msg = throwable.message.orEmpty()
                    if (msg.contains("Connection reset", true) || msg.contains("Broken pipe", true)) {
                        Network(Network.Kind.RESET, throwable)
                    } else {
                        Network(Network.Kind.UNREACHABLE, throwable)
                    }
                }
                is retrofit2.HttpException -> {
                    val code = throwable.code()
                    when {
                        // 401 不再无差别映射为 SESSION_EXPIRED：
                        // 公开接口（首页/搜索/帖子详情）的 401 与登录状态无关，
                        // 映射为 Server(401) 显示通用错误文案，避免误导用户"登录已失效"。
                        // 真正的会话失效由 AuthRepository.apiResultToResult() 处理。
                        code == 401 -> Server(401, throwable.message(), throwable)
                        code == 403 -> Server(403, throwable.message(), throwable)
                        else -> Server(code, throwable.message(), throwable)
                    }
                }
                else -> {
                    val msg = throwable.message.orEmpty()
                    when {
                        msg.contains("Cloudflare", true) -> CloudflareChallenge(throwable)
                        else -> Unknown(throwable)
                    }
                }
            }
        }
    }
}
