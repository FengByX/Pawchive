package com.pawchive.data.github

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.R
import kotlinx.coroutines.launch

sealed class UpdateResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String
    ) : UpdateResult()

    data object UpToDate : UpdateResult()
    data object Error : UpdateResult()
}

class UpdateChecker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val githubApi: GithubApi = GithubApi.create()

    /**
     * 检查是否有新版本更新
     * 返回检查结果，由调用方决定是否显示弹窗
     */
    suspend fun check(currentVersion: String): UpdateResult {
        return try {
            val release = githubApi.getLatestRelease()
            val latestVersion = release.tagName.removePrefix("v")
            val current = currentVersion.removePrefix("v")

            if (isNewerVersion(latestVersion, current)) {
                UpdateResult.UpdateAvailable(
                    latestVersion = release.tagName,
                    currentVersion = currentVersion,
                    releaseNotes = release.body ?: "",
                    downloadUrl = release.htmlUrl
                )
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Error
        }
    }

    /**
     * 检查并弹窗提示（带 24 小时间隔限制）
     * 在 LifecycleOwner（Activity/Fragment）上下文中调用
     */
    fun checkAndShowDialog(
        lifecycleOwner: LifecycleOwner,
        currentVersion: String,
        context: Context
    ) {
        if (shouldSkipCheck()) {
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            recordCheckTime()
            val result = check(currentVersion)

            if (result is UpdateResult.UpdateAvailable) {
                showUpdateDialog(context, result)
            }
        }
    }

    /**
     * 语义化版本号比较
     * 支持格式："1.0.10" > "1.0.9"
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".")
        val currentParts = current.split(".")
        val maxLength = maxOf(latestParts.size, currentParts.size)

        for (i in 0 until maxLength) {
            val latestPart = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
            val currentPart = currentParts.getOrNull(i)?.toIntOrNull() ?: 0

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    /**
     * 检查是否应该跳过（24 小时间隔）
     */
    private fun shouldSkipCheck(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        if (lastCheck == 0L) return false
        return (System.currentTimeMillis() - lastCheck) < CHECK_INTERVAL_MS
    }

    /**
     * 记录本次检查时间
     */
    private fun recordCheckTime() {
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * 显示更新提示弹窗
     */
    private fun showUpdateDialog(context: Context, result: UpdateResult.UpdateAvailable) {
        val notes = if (result.releaseNotes.isNotBlank()) {
            result.releaseNotes
        } else {
            context.getString(R.string.update_available_title)
        }

        val currentVersionText = context.getString(
            R.string.update_current_version,
            result.currentVersion
        )
        val latestVersionText = context.getString(
            R.string.update_latest_version,
            result.latestVersion
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setMessage("$currentVersionText\n$latestVersionText\n\n$notes")
            .setPositiveButton(R.string.update_go_download) { _, _ ->
                openDownloadPage(context, result.downloadUrl)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * 使用 Chrome Custom Tabs 打开下载页面
     */
    private fun openDownloadPage(context: Context, url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, Uri.parse(url))
    }

    companion object {
        private const val PREFS_NAME = "update_checker_prefs"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 小时
    }
}
