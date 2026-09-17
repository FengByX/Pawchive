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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.data.R
import com.pawchive.core.api.GithubApi
import com.pawchive.core.store.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// 顶层 DataStore 单例（更新检查时间戳）
private val Context.updateCheckerDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_checker_prefs")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常（补充②）
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

class UpdateChecker @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsManager: SettingsManager
) {

    private val dataStore = context.updateCheckerDataStore
    private val githubApi: GithubApi = GithubApi.create()

    // 内存缓存快照（shouldSkipCheck 同步调用）：初始为 0，构造后在 IO 线程异步加载，
    // 避免主线程构造时 runBlocking（补充②）
    @Volatile
    private var lastCheckTime: Long = 0L

    /** 用户选择"忽略此版本"的 tag（如 "v1.7.2"）。null 表示未忽略。 */
    @Volatile
    private var ignoredVersion: String? = null

    init {
        ioScope.launch {
            runCatching {
                lastCheckTime = dataStore.data.first()[KEY_LAST_CHECK_TIME] ?: 0L
                ignoredVersion = dataStore.data.first()[KEY_IGNORED_VERSION]
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * 检查是否有新版本更新
     * 返回检查结果，由调用方决定是否显示弹窗
     *
     * 通道语义（FEATURE：设置项扩展批次三·更新通道）：
     * - STABLE：GitHub releases/latest（最新正式版）；
     * - BETA：全量发布列表（剔除草稿）中语义化版本最新的一条（含 beta/rc 预发布）。
     */
    suspend fun check(currentVersion: String): UpdateResult {
        return try {
            val release = when (settingsManager.getUpdateChannel()) {
                SettingsManager.UpdateChannel.STABLE -> githubApi.getLatestRelease()
                SettingsManager.UpdateChannel.BETA -> githubApi.getReleases()
                    .filter { !it.draft && it.tagName.isNotBlank() }
                    .maxWithOrNull { a, b ->
                        compareSemver(
                            a.tagName.removePrefix("v"),
                            b.tagName.removePrefix("v")
                        )
                    }
            } ?: return UpdateResult.Error

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
        // 用户在设置中关闭了自动检查更新时，跳过启动时的更新检查
        if (!settingsManager.isAutoCheckUpdateEnabled()) {
            return
        }
        if (shouldSkipCheck()) {
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            recordCheckTime()
            val result = check(currentVersion)

            if (result is UpdateResult.UpdateAvailable) {
                // 用户对该版本点了"忽略此版本"：静默跳过（手动"检查更新"不受影响，仍会提示）
                if (result.latestVersion == ignoredVersion) return@launch
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
    @JvmSynthetic
    internal fun isNewerVersion(latest: String, current: String): Boolean {
        return compareSemver(latest, current) > 0
    }

    /**
     * 语义化版本号比较（FEATURE：Beta 通道需要从全量发布中选取最新）。
     * 返回正数表示 a 更新，负数表示 b 更新，0 表示相等。
     * 预发布后缀（-beta / -rc.1）比同号正式版旧（语义化版本约定）。
     */
    @JvmSynthetic
    internal fun compareSemver(a: String, b: String): Int {
        val av = parseSemver(a)
        val bv = parseSemver(b)

        val maxLength = maxOf(av.numeric.size, bv.numeric.size)
        for (i in 0 until maxLength) {
            val x = av.numeric.getOrNull(i) ?: 0
            val y = bv.numeric.getOrNull(i) ?: 0
            if (x > y) return 1
            if (x < y) return -1
        }

        return when {
            av.preRelease.isNullOrEmpty() && bv.preRelease.isNullOrEmpty() -> 0
            av.preRelease.isNullOrEmpty() -> 1   // a 为正式版，b 为预发布 → a 更新
            bv.preRelease.isNullOrEmpty() -> -1  // a 为预发布，b 为正式版 → b 更新
            else -> av.preRelease.compareTo(bv.preRelease)
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
     * 检查是否应该跳过（间隔由设置页"更新检查频率"决定）。
     * - 每次启动：从不跳过
     * - 每天（默认）：24 小时
     * - 每周：7 天
     */
    private fun shouldSkipCheck(): Boolean {
        if (lastCheckTime == 0L) return false
        val intervalMs = when (settingsManager.getUpdateFrequency()) {
            SettingsManager.UpdateFrequency.EVERY_LAUNCH -> return false
            SettingsManager.UpdateFrequency.DAILY -> CHECK_INTERVAL_DAILY_MS
            SettingsManager.UpdateFrequency.WEEKLY -> CHECK_INTERVAL_WEEKLY_MS
        }
        return (System.currentTimeMillis() - lastCheckTime) < intervalMs
    }

    /**
     * 记录本次检查时间
     */
    private fun recordCheckTime() {
        val now = System.currentTimeMillis()
        lastCheckTime = now
        ioScope.launch {
            runCatching { dataStore.edit { it[KEY_LAST_CHECK_TIME] = now } }
                .onFailure { it.printStackTrace() }
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
            textSize = 14f
            val textColor = TypedValue().let {
                context.theme.resolveAttribute(android.R.attr.textColorPrimary, it, true)
                it.data
            }
            setTextColor(textColor)
            val fullHtml = buildString {
                append("<font color=\"#808080\">")
                append("<small>")
                append(headerText.replace("\n", "<br>"))
                append("</small>")
                append("</font>")
                if (notesHtml.isNotEmpty()) {
                    append("<br><br>")
                    append(notesHtml)
                }
            }
            text = com.pawchive.core.util.SafeHtmlHelper.render(fullHtml)
            movementMethod = LinkMovementMethod.getInstance()
            setLineSpacing(dpToPx(context, 4).toFloat(), 1.0f)
        }
        scrollView.addView(contentTextView)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setView(scrollView)
            .setPositiveButton(R.string.update_go_download) { _, _ ->
                openDownloadPage(context, result.downloadUrl)
            }
            // FEATURE：忽略此版本——之后该版本的启动自动检查不再提示（手动检查不受限）
            .setNeutralButton(R.string.update_ignore_version) { _, _ ->
                ignoredVersion = result.latestVersion
                ioScope.launch {
                    runCatching {
                        dataStore.edit { it[KEY_IGNORED_VERSION] = result.latestVersion }
                    }.onFailure { it.printStackTrace() }
                }
                android.widget.Toast.makeText(
                    context,
                    R.string.update_version_ignored,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * Markdown 到 HTML 转换（兼容 Html.fromHtml 的有限标签集）
     * 支持：标题、粗体、斜体、列表、换行、代码
     */
    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inList = false

        fun closeList() {
            if (inList) {
                sb.append("<br>")
                inList = false
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            when {
                // 四级及以上标题 → 粗体
                trimmed.startsWith("#### ") || trimmed.startsWith("##### ") || trimmed.startsWith("###### ") -> {
                    closeList()
                    sb.append("<br><b>").append(renderInline(trimmed.substring(trimmed.indexOf(' ') + 1))).append("</b><br>")
                }
                // 三级标题 → 粗体 + 加大
                trimmed.startsWith("### ") -> {
                    closeList()
                    sb.append("<br><b><big>").append(renderInline(trimmed.substring(4))).append("</big></b><br>")
                }
                // 二级标题 → 粗体 + 加大 + 间距
                trimmed.startsWith("## ") -> {
                    closeList()
                    sb.append("<br><b><big>").append(renderInline(trimmed.substring(3))).append("</big></b><br>")
                }
                // 一级标题 → 粗体 + 加大 + 间距
                trimmed.startsWith("# ") -> {
                    closeList()
                    sb.append("<br><b><big>").append(renderInline(trimmed.substring(2))).append("</big></b><br>")
                }
                // 列表项
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    sb.append("• ").append(renderInline(trimmed.substring(2))).append("<br>")
                    inList = true
                }
                // 引用
                trimmed.startsWith("> ") -> {
                    closeList()
                    sb.append("<blockquote>").append(renderInline(trimmed.substring(2))).append("</blockquote>")
                }
                // 空行
                trimmed.isEmpty() -> {
                    closeList()
                    sb.append("<br>")
                }
                // 普通文本
                else -> {
                    closeList()
                    sb.append(renderInline(trimmed)).append("<br>")
                }
            }
        }

        if (inList) sb.append("<br>")
        return sb.toString()
    }

    /**
     * 渲染 Markdown 内联格式：粗体、斜体、行内代码、链接
     */
    private fun renderInline(text: String): String {
        var result = text
        // 行内代码
        result = result.replace(Regex("`([^`]+)`"), "<tt>$1</tt>")
        // 粗体
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        result = result.replace(Regex("__(.+?)__"), "<b>$1</b>")
        // 斜体
        result = result.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        result = result.replace(Regex("_(.+?)_"), "<i>$1</i>")
        // 链接 [text](url)
        result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return result
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

        /** 用户选择"忽略此版本"的版本 tag。 */
        private val KEY_IGNORED_VERSION = stringPreferencesKey("ignored_version")

        private const val CHECK_INTERVAL_DAILY_MS = 24 * 60 * 60 * 1000L   // 24 小时（默认）
        private const val CHECK_INTERVAL_WEEKLY_MS = 7 * CHECK_INTERVAL_DAILY_MS // 每周
    }
}
