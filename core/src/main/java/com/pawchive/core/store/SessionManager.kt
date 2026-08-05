package com.pawchive.core.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理 Pawchive 会话和认证状态。
 *
 * 安全策略（P1）：
 * - session cookie 仅写入 EncryptedSharedPreferences，禁止明文降级；
 * - 加密初始化失败时清理损坏密钥/文件并重试一次；
 * - 仍失败则进入"加密不可用"状态：读取返回 null（视为未登录），写入直接丢弃，
 *   调用方应引导用户重新登录，绝不回退到明文 SharedPreferences。
 *
 * 多账号支持（FEATURE-003）：
 * - 保存多个账号的凭据（用户名 + cookie），支持快速切换；
 * - 切换账号时清除当前账号的本地数据（收藏/历史/下载），实现数据隔离。
 *
 * ARCH-003：已迁移至 Hilt 构造函数注入（@Singleton），由 DI 容器统一管理生命周期。
 */
@Singleton
class SessionManager @Inject constructor(@ApplicationContext context: Context) {

    @Volatile
    private var prefs: SharedPreferences? = createEncryptedPrefs(context)

    val isEncryptedStorageAvailable: Boolean get() = prefs != null

    private fun createEncryptedPrefs(context: Context): SharedPreferences? {
        createEncryptedPrefsInternal(context)?.let { return it }
        // ARCH-008：清理损坏的加密存储文件后重试；失败仅记录日志
        try {
            context.deleteSharedPreferences(PREFS_FILE_NAME)
        } catch (e: Exception) {
            Log.w("SessionManager", "deleteSharedPreferences failed", e)
        }
        return createEncryptedPrefsInternal(context)
    }

    private fun createEncryptedPrefsInternal(context: Context): SharedPreferences? {
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
        } catch (_: Exception) { null }
    }

    fun saveSession(cookie: String): Boolean {
        val sp = prefs ?: return false
        sp.edit()
            .putString(KEY_SESSION_COOKIE, cookie)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        return true
    }

    fun getSessionCookie(): String? = prefs?.getString(KEY_SESSION_COOKIE, null)

    fun isLoggedIn(): Boolean {
        val sp = prefs ?: return false
        return sp.getBoolean(KEY_IS_LOGGED_IN, false) && !getSessionCookie().isNullOrEmpty()
    }

    fun saveUsername(username: String): Boolean {
        val sp = prefs ?: return false
        sp.edit().putString(KEY_USERNAME, username).apply()
        return true
    }

    fun getUsername(): String? = prefs?.getString(KEY_USERNAME, null)

    fun clearSession() {
        prefs?.edit()
            ?.remove(KEY_SESSION_COOKIE)
            ?.remove(KEY_IS_LOGGED_IN)
            ?.remove(KEY_USERNAME)
            ?.apply()
    }

    // ===== 多账号管理（FEATURE-003）=====

    /**
     * 保存当前账号到账号列表（用于后续切换）。
     * 以 username 为唯一标识，重复保存会覆盖旧凭据。
     */
    fun saveAccountToList(username: String, cookie: String): Boolean {
        val sp = prefs ?: return false
        val accounts = getSavedAccounts().toMutableMap()
        accounts[username] = cookie
        sp.edit()
            .putString(KEY_ACCOUNT_LIST, serializeAccounts(accounts))
            .apply()
        return true
    }

    /**
     * 获取所有已保存的账号列表（username → cookie）。
     */
    fun getSavedAccounts(): Map<String, String> {
        val sp = prefs ?: return emptyMap()
        val raw = sp.getString(KEY_ACCOUNT_LIST, null) ?: return emptyMap()
        return deserializeAccounts(raw)
    }

    /**
     * 切换到指定账号。成功返回 true。
     * 调用方应在切换前清除当前账号的本地数据以实现数据隔离。
     */
    fun switchToAccount(username: String): Boolean {
        val sp = prefs ?: return false
        val accounts = getSavedAccounts()
        val cookie = accounts[username] ?: return false
        sp.edit()
            .putString(KEY_SESSION_COOKIE, cookie)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USERNAME, username)
            .apply()
        return true
    }

    /**
     * 从账号列表中移除指定账号。
     */
    fun removeAccount(username: String) {
        val sp = prefs ?: return
        val accounts = getSavedAccounts().toMutableMap()
        accounts.remove(username)
        sp.edit()
            .putString(KEY_ACCOUNT_LIST, serializeAccounts(accounts))
            .apply()
    }

    private fun serializeAccounts(accounts: Map<String, String>): String {
        if (accounts.isEmpty()) return ""
        return accounts.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    private fun deserializeAccounts(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    companion object {
        private const val PREFS_FILE_NAME = "pawchive_session"
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USERNAME = "username"
        private const val KEY_ACCOUNT_LIST = "account_list"
    }
}
