package com.pawchive.data.github

import android.content.Context
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.setPadding
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 顶层 DataStore 单例（更新检查时间戳）
private val Context.updateCheckerDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_checker_prefs")

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

    private val dataStore = context.updateCheckerDataStore
    private val githubApi: GithubApi = GithubApi.create()

    // 内存缓存快照（shouldSkipCheck 同步调用，避免每次 runBlocking）
    @Volatile
    private var lastCheckTime: Long = runBlocking {
        dataStore.data.first()[KEY_LAST_CHECK_TIME] ?: 0L
    }

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
     * 语义化版本号比较。
     * 支持格式："1.0.10" > "1.0.9"。
     * 也支持带预发布后缀的版本号，如 "1.2.0-beta"、"1.2.0-rc.1"：
     *  - 数字部分按数值比较；
     *  - 后缀（如 -beta / -rc.1）视为预发布版本，比同号无后缀版本旧（语义化版本约定）。
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestVer = parseSemver(latest)
        val currentVer = parseSemver(current)

        // 逐段比较数字部分
        val maxLength = maxOf(latestVer.numeric.size, currentVer.numeric.size)
        for (i in 0 until maxLength) {
            val l = latestVer.numeric.getOrNull(i) ?: 0
            val c = currentVer.numeric.getOrNull(i) ?: 0
            if (l > c) return true
            if (l < c) return false
        }

        // 数字部分完全相等时，按预发布后缀判定：
        //  - 都无后缀：相等，不算新版本
        //  - latest 无后缀、current 有后缀：latest 是正式版，current 是预发布，latest 更新
        //  - latest 有后缀、current 无后缀：latest 是预发布，不算更新
        //  - 都有后缀：按字符串比较（保守起见）
        return when {
            latestVer.preRelease.isNullOrEmpty() && !currentVer.preRelease.isNullOrEmpty() -> true
            !latestVer.preRelease.isNullOrEmpty() && currentVer.preRelease.isNullOrEmpty() -> false
            !latestVer.preRelease.isNullOrEmpty() && !currentVer.preRelease.isNullOrEmpty() ->
                latestVer.preRelease > currentVer.preRelease
            else -> false
        }
    }

    private data class Semver(val numeric: List<Int>, val preRelease: String?)

    private fun parseSemver(version: String): Semver {
        // 仅去除首尾空白，不去除 'v' 前缀由调用方处理
        val trimmed = version.trim()
        // 拆分预发布后缀
        val (core, pre) = if (trimmed.contains('-')) {
            val idx = trimmed.indexOf('-')
            trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        } else {
            trimmed to null
        }
        val numeric = core.split(".")
            .mapNotNull { it.toIntOrNull() }
        return Semver(numeric, pre?.takeIf { it.isNotEmpty() })
    }

    /**
     * 检查是否应该跳过（24 小时间隔）
     */
    private fun shouldSkipCheck(): Boolean {
        if (lastCheckTime == 0L) return false
        return (System.currentTimeMillis() - lastCheckTime) < CHECK_INTERVAL_MS
    }

    /**
     * 记录本次检查时间
     */
    private fun recordCheckTime() {
        val now = System.currentTimeMillis()
        lastCheckTime = now
        kotlinx.coroutines.GlobalScope.launch {
            dataStore.edit { it[KEY_LAST_CHECK_TIME] = now }
        }
    }

    /**
     * 显示更新提示弹窗
     */
    private fun showUpdateDialog(context: Context, result: UpdateResult.UpdateAvailable) {
        val currentVersionText = context.getString(
            R.string.update_current_version,
            result.currentVersion
        )
        val latestVersionText = context.getString(
            R.string.update_latest_version,
            result.latestVersion
        )

        val headerText = "$currentVersionText\n$latestVersionText"
        val notesHtml = if (result.releaseNotes.isNotBlank()) {
            markdownToHtml(result.releaseNotes)
        } else {
            ""
        }

        val scrollView = ScrollView(context).apply {
            setPadding(dpToPx(context, 24))
        }
        val contentTextView = TextView(context).apply {
            textSize = 15f
            val textColor = TypedValue().let {
                context.theme.resolveAttribute(android.R.attr.textColorSecondary, it, true)
                it.data
            }
            setTextColor(textColor)
            val fullHtml = buildString {
                append("<font color=\"#808080\">")
                append(headerText.replace("\n", "<br>"))
                append("</font>")
                if (notesHtml.isNotEmpty()) {
                    append("<br><br>")
                    append(notesHtml)
                }
            }
            text = Html.fromHtml(fullHtml, Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
        }
        scrollView.addView(contentTextView)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setView(scrollView)
            .setPositiveButton(R.string.update_go_download) { _, _ ->
                openDownloadPage(context, result.downloadUrl)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * 简单的 Markdown 到 HTML 转换
     * 支持：标题、粗体、斜体、列表、换行
     * 使用 Html.fromHtml 原生支持的标签（无 CSS style）
     */
    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inList = false

        fun closeList() {
            if (inList) {
                sb.append("</ul>")
                inList = false
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            when {
                // 三级标题
                trimmed.startsWith("### ") -> {
                    closeList()
                    sb.append("<h3><b>").append(renderInline(trimmed.substring(4))).append("</b></h3>")
                }
                // 二级标题
                trimmed.startsWith("## ") -> {
                    closeList()
                    sb.append("<h2><b>").append(renderInline(trimmed.substring(3))).append("</b></h2>")
                }
                // 一级标题
                trimmed.startsWith("# ") -> {
                    closeList()
                    sb.append("<h1><b>").append(renderInline(trimmed.substring(2))).append("</b></h1>")
                }
                // 列表项
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (!inList) {
                        sb.append("<ul>")
                        inList = true
                    }
                    sb.append("<li>").append(renderInline(trimmed.substring(2))).append("</li>")
                }
                // 空行
                trimmed.isEmpty() -> {
                    closeList()
                    sb.append("<br>")
                }
                // 普通文本
                else -> {
                    closeList()
                    sb.append("<p>").append(renderInline(trimmed)).append("</p>")
                }
            }
        }

        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    /**
     * 渲染 Markdown 内联格式：粗体、斜体、行内代码
     */
    private fun renderInline(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("__(.+?)__"), "<b>$1</b>")
            .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
            .replace(Regex("_(.+?)_"), "<i>$1</i>")
            .replace(Regex("`(.+?)`"), "<tt>$1</tt>")
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
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
        private val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 小时
    }
}
