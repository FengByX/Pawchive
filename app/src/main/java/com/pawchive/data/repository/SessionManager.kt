package com.pawchive.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 管理 Pawchive 会话和认证状态
 * 使用 EncryptedSharedPreferences 存储 session cookie，加密初始化失败时回退到普通 SharedPreferences
 */
class SessionManager(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)
        ?: context.getSharedPreferences(FALLBACK_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_FILE_NAME = "pawchive_session"
        private const val FALLBACK_PREFS_FILE_NAME = "pawchive_session_fallback"
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USERNAME = "username"

        private fun createEncryptedPrefs(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // 加密初始化失败（如主密钥损坏），清理可能损坏的加密文件后回退到独立的普通文件，
                // 避免与加密文件同名导致读取加密数据格式冲突而再次崩溃
                try {
                    context.deleteSharedPreferences(PREFS_FILE_NAME)
                } catch (_: Exception) {}
                null
            }
        }
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
