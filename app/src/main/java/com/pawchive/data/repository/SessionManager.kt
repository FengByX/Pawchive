package com.pawchive.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 管理 Pawchive 会话和认证状态
 * 使用 SharedPreferences 存储 session cookie
 */
class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pawchive_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USERNAME = "username"
    }

    /**
     * 保存会话 cookie
     */
    fun saveSession(cookie: String) {
        prefs.edit()
            .putString(KEY_SESSION_COOKIE, cookie)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    /**
     * 获取会话 cookie
     */
    fun getSessionCookie(): String? {
        return prefs.getString(KEY_SESSION_COOKIE, null)
    }

    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false) && !getSessionCookie().isNullOrEmpty()
    }

    /**
     * 保存用户名
     */
    fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    /**
     * 获取用户名
     */
    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * 清除会话（登出）
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_COOKIE)
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_USERNAME)
            .apply()
    }
}