package com.pawchive.core.error

import android.content.Context
import android.util.Log
import com.pawchive.core.R
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.io.IOException

object ErrorMessageHelper {

    private const val TAG = "ErrorMessageHelper"

    fun getFriendlyMessage(context: Context?, throwable: Throwable?): String {
        if (context == null) return "操作失败，请稍后重试"
        if (throwable == null) return context.getString(R.string.error_unknown)
        // 优先使用 AppError 的结构化映射，避免脆弱的字符串匹配（P2 BACKEND-007）
        val appError = (throwable as? AppError) ?: AppError.from(throwable)
        return appError.toMessage(context)
    }

    fun getFriendlyMessage(context: Context?, rawMessage: String?): String {
        if (context == null) return "操作失败，请稍后重试"
        if (rawMessage.isNullOrBlank()) return context.getString(R.string.error_unknown)
        return mapMessage(context, "", rawMessage)
    }

    private fun mapMessage(context: Context?, className: String, message: String): String {
        if (context == null) return "操作失败，请稍后重试"
        return when {
            className == "UnknownHostException" ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated", ignoreCase = true) ->
                context.getString(R.string.error_network_unreachable)

            className == "ConnectException" ->
                context.getString(R.string.error_server_unreachable)

            className == "SocketTimeoutException" ||
                message.contains("Connection timed out", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) ->
                context.getString(R.string.error_timeout)

            className == "SSLException" ||
                className == "SSLPeerUnverifiedException" ->
                context.getString(R.string.error_ssl)

            message.contains("Connection reset", ignoreCase = true) ||
                message.contains("Broken pipe", ignoreCase = true) ->
                context.getString(R.string.error_connection_reset)

            message.contains("403", ignoreCase = true) ->
                context.getString(R.string.error_forbidden)

            message.contains("404", ignoreCase = true) ->
                context.getString(R.string.error_not_found)

            message.contains("500", ignoreCase = true) ||
                message.contains("502", ignoreCase = true) ||
                message.contains("503", ignoreCase = true) ->
                context.getString(R.string.error_server_error)

            message.contains("Cloudflare", ignoreCase = true) ->
                context.getString(R.string.error_cloudflare)

            message.contains("login", ignoreCase = true) ||
                message.contains("auth", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) ->
                context.getString(R.string.error_auth)

            else -> {
                Log.w(TAG, "Unhandled error: $className - $message")
                context.getString(R.string.error_unknown)
            }
        }
    }

    private fun findRootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }
}
