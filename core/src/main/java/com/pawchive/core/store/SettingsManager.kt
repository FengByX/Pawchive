package com.pawchive.core.store

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// 顶层 DataStore 单例（DataStore 必须是单例，每个文件名只允许一个实例）
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 应用设置管理器（ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * 由 `@Singleton` + `@Inject constructor` 管理 lifecycle，不再通过 `getInstance()` 暴露单例。
 * 启动期路径（`MainActivity.attachBaseContext` / `applyAppearance`）通过
 * `PawchiveApplication.getSettingsManager()` 读取已注入的实例，并只访问轻量同步启动缓存
 * （SharedPreferences，ARCH-007），不读取 DataStore、不阻塞主线程。
 */
@Singleton
class SettingsManager @Inject constructor(@ApplicationContext context: Context) {

    enum class Language(val displayName: String, val code: String) {
        CHINESE("中文", "zh"),
        ENGLISH("English", "en"),
        JAPANESE("日本語", "ja")
    }

    enum class Appearance(val displayName: String, val mode: Int) {
        LIGHT("日间模式", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("夜间模式", AppCompatDelegate.MODE_NIGHT_YES),
        FOLLOW_SYSTEM("跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    /**
     * 启动主界面 Tab（FEATURE：启动页可配置）。
     * 用户进入 App 后默认停留的主 Tab，可在设置页修改。
     */
    enum class StartupTab(val key: String) {
        HOME("home"),
        SEARCH("search"),
        BOOKMARKS("bookmarks"),
        ACCOUNT("account"),
        DOWNLOADS("downloads");

        companion object {
            fun fromKey(key: String?): StartupTab =
                entries.find { it.key == key } ?: BOOKMARKS
        }
    }

    private val dataStore = context.appSettingsDataStore

    // 轻量同步启动缓存（SharedPreferences，ARCH-007）：
    // 启动期路径（attachBaseContext / applyAppearance）只读本缓存，零磁盘等待。
    private val startupPrefs: SharedPreferences =
        context.getSharedPreferences("pawchive_startup_cache", Context.MODE_PRIVATE)

    // 内存缓存快照：初始为空（全部使用默认值），构造后后台异步预加载真实数据。
    // 常规读取（设置页等）发生在应用启动之后，此时异步加载已完成；启动期路径读 startupPrefs。
    @Volatile
    private var settingsCache: Preferences = emptyPreferences()

    // 是否已有用户写入：异步预加载不得覆盖用户刚写入的值（防竞态）
    @Volatile
    private var userWrote = false

    // 串行化 settingsCache / userWrote 的读改写（ARCH-007）：
    // 构造异步加载与 write() 并发修改内存快照，必须原子地执行"检查 userWrote + 赋值"，
    // 否则加载协程可能用旧快照覆盖写入后的内存，导致刚保存的设置读回默认值。
    private val cacheLock = Any()

    init {
        // ARCH-007：构造不再同步读盘（原 runBlocking 首构造已移除）。
        // 后台异步预加载真实数据，并把既有 DataStore 值种子化到启动缓存（老版本迁移）。
        ioScope.launch {
            runCatching {
                val loaded = dataStore.data.first()
                synchronized(cacheLock) {
                    if (!userWrote) settingsCache = loaded
                }
                seedStartupCache(loaded)
            }
        }
    }

    fun getLanguage(): Language {
        val code = read { it[KEY_LANGUAGE] } ?: "zh"
        return Language.entries.find { it.code == code } ?: Language.CHINESE
    }

    /**
     * 启动期语言读取（ARCH-007）：仅读轻量 SharedPreferences，无 DataStore、无阻塞。
     * 仅供 MainActivity.attachBaseContext 等启动期路径使用；常规读取走 [getLanguage]。
     */
    fun getStartupLanguage(): Language {
        val code = startupPrefs.getString(KEY_STARTUP_LANGUAGE, null)
            ?: read { it[KEY_LANGUAGE] }
            ?: "zh"
        return Language.entries.find { it.code == code } ?: Language.CHINESE
    }

    fun setLanguage(language: Language) {
        write { it[KEY_LANGUAGE] = language.code }
        startupPrefs.edit().putString(KEY_STARTUP_LANGUAGE, language.code).apply()
        // ARCH-007：同步到 AndroidX AppCompat locale 持久化机制（autoStoreLocales 元数据开启后
        // AppCompat 自行持久化并在 attachBaseContext 自动应用）。失败不影响已落盘的
        // DataStore + startupPrefs（手动 attachBaseContext 兜底），仅记录日志。
        runCatching {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.code))
        }.onFailure {
            android.util.Log.w("SettingsManager", "setApplicationLocales failed", it)
        }
    }

    fun getAppearance(): Appearance {
        val name = read { it[KEY_APPEARANCE] } ?: Appearance.FOLLOW_SYSTEM.name
        return try { Appearance.valueOf(name) } catch (_: Exception) { Appearance.FOLLOW_SYSTEM }
    }

    /**
     * 启动主界面 Tab（默认收藏）。启动期读取走 [getStartupTabStartup] 轻量缓存。
     */
    fun getStartupTab(): StartupTab {
        val key = read { it[KEY_STARTUP_TAB] } ?: StartupTab.BOOKMARKS.key
        return StartupTab.fromKey(key)
    }

    fun setStartupTab(tab: StartupTab) {
        write { it[KEY_STARTUP_TAB] = tab.key }
        startupPrefs.edit().putString(KEY_STARTUP_TAB_STARTUP, tab.key).apply()
    }

    /**
     * 启动期启动 Tab 读取（ARCH-007）：仅读轻量 SharedPreferences，无阻塞。
     */
    fun getStartupTabStartup(): StartupTab {
        val key = startupPrefs.getString(KEY_STARTUP_TAB_STARTUP, null)
            ?: read { it[KEY_STARTUP_TAB] }
            ?: StartupTab.BOOKMARKS.key
        return StartupTab.fromKey(key)
    }

    fun setAppearance(appearance: Appearance) {
        write { it[KEY_APPEARANCE] = appearance.name }
        startupPrefs.edit().putString(KEY_STARTUP_APPEARANCE, appearance.name).apply()
        AppCompatDelegate.setDefaultNightMode(appearance.mode)
    }

    /**
     * 应用外观模式（ARCH-007）：读取轻量同步启动缓存，零磁盘 I/O。
     * 调用方应注入 SettingsManager 后调用此方法，例如：
     * ```
     * @Inject lateinit var settingsManager: SettingsManager
     * settingsManager.applyAppearance()
     * ```
     */
    fun applyAppearance() {
        val name = startupPrefs.getString(KEY_STARTUP_APPEARANCE, null)
            ?: read { it[KEY_APPEARANCE] }
            ?: Appearance.FOLLOW_SYSTEM.name
        val appearance = try { Appearance.valueOf(name) } catch (_: Exception) { Appearance.FOLLOW_SYSTEM }
        AppCompatDelegate.setDefaultNightMode(appearance.mode)
    }

    fun getDownloadTreeUri(): Uri? {
        val uriString = read { it[KEY_DOWNLOAD_TREE_URI] } ?: return null
        return try { Uri.parse(uriString) } catch (_: Exception) { null }
    }

    fun setDownloadTreeUri(uri: Uri?, displayName: String) {
        write { prefs ->
            if (uri != null) {
                prefs[KEY_DOWNLOAD_TREE_URI] = uri.toString()
                prefs[KEY_DOWNLOAD_LOCATION_NAME] = displayName
            } else {
                prefs.remove(KEY_DOWNLOAD_TREE_URI)
                prefs.remove(KEY_DOWNLOAD_LOCATION_NAME)
            }
        }
    }

    fun getDownloadLocationName(): String {
        return read { it[KEY_DOWNLOAD_LOCATION_NAME] } ?: ""
    }

    fun isAutoCleanCacheEnabled(): Boolean {
        return read { it[KEY_AUTO_CLEAN_CACHE] } ?: false
    }

    fun setAutoCleanCacheEnabled(enabled: Boolean) {
        write { it[KEY_AUTO_CLEAN_CACHE] = enabled }
    }

    fun isAutoCheckUpdateEnabled(): Boolean {
        return read { it[KEY_AUTO_CHECK_UPDATE] } ?: true
    }

    fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        write { it[KEY_AUTO_CHECK_UPDATE] = enabled }
    }

    /**
     * 首页是否隐藏已收藏创作者的帖子（ARCH-FEATURE-006）。
     * 默认关闭；开启后首页信息流过滤掉已收藏创作者发布的内容。
     */
    fun isHideBookmarkedCreatorsEnabled(): Boolean {
        return read { it[KEY_HIDE_BOOKMARKED_CREATORS] } ?: false
    }

    fun setHideBookmarkedCreatorsEnabled(enabled: Boolean) {
        write { it[KEY_HIDE_BOOKMARKED_CREATORS] = enabled }
    }

    /**
     * 首页同作者仅显示一条（FEATURE）。
     * 默认开启；开启后首页信息流中同一创作者只展示最新的一条作品。
     */
    fun isDedupeByCreatorEnabled(): Boolean {
        return read { it[KEY_DEDUPE_BY_CREATOR] } ?: true
    }

    fun setDedupeByCreatorEnabled(enabled: Boolean) {
        write { it[KEY_DEDUPE_BY_CREATOR] = enabled }
    }

    /**
     * 收藏创作者时自动订阅（ARCH-FEATURE-003 联动遗留项）。
     * 默认开启：收藏即关注，该创作者的新帖自动进入内容更新订阅。
     * 可在设置页关闭；取消收藏不会自动退订（退订是用户显式意图）。
     */
    fun isAutoSubscribeOnBookmarkEnabled(): Boolean {
        return read { it[KEY_AUTO_SUBSCRIBE_ON_BOOKMARK] } ?: true
    }

    fun setAutoSubscribeOnBookmarkEnabled(enabled: Boolean) {
        write { it[KEY_AUTO_SUBSCRIBE_ON_BOOKMARK] = enabled }
    }

    /**
     * 上次缓存清理的时间戳（毫秒）。0 表示从未清理。
     * 用于 FRONTEND-003：展示上次清理时间，避免每次启动无条件清空。
     */
    fun getLastCacheCleanTime(): Long {
        return read { it[KEY_LAST_CACHE_CLEAN_TIME] } ?: 0L
    }

    fun setLastCacheCleanTime(timestamp: Long) {
        write { it[KEY_LAST_CACHE_CLEAN_TIME] = timestamp }
    }

    /**
     * 缓存容量阈值（字节）。超过此值时自动触发清理（FRONTEND-003）。
     * 默认 200MB，与图片磁盘缓存上限 100MB + 其他临时文件相匹配。
     */
    fun getCacheThresholdBytes(): Long {
        return read { it[KEY_CACHE_THRESHOLD_BYTES] } ?: DEFAULT_CACHE_THRESHOLD_BYTES
    }

    fun setCacheThresholdBytes(bytes: Long) {
        write { it[KEY_CACHE_THRESHOLD_BYTES] = bytes }
    }

    /**
     * 是否应基于容量阈值触发自动清理（而非每次启动无条件清空）。
     * - 缓存大小超过阈值时返回 true
     * - 从未清理过时返回 true（首次启动场景）
     */
    fun shouldAutoCleanByThreshold(context: Context): Boolean {
        if (getLastCacheCleanTime() == 0L) return true
        val currentSize = getCacheSize(context)
        return currentSize > getCacheThresholdBytes()
    }

    /**
     * 同步读取内存缓存快照（无磁盘 I/O、无 runBlocking）。
     */
    private fun <T> read(block: (Preferences) -> T): T = block(settingsCache)

    /**
     * 异步写入：更新内存缓存快照 + 在 IO 线程落盘（带异常处理）。
     * 写入后立即更新内存缓存，保证后续同步读取立即可见。
     */
    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        synchronized(cacheLock) {
            settingsCache = settingsCache.toMutablePreferences().apply { block(this) }
            userWrote = true
        }
        ioScope.launch {
            // 注意：不再用 dataStore.edit 的返回值整体回写内存快照（ARCH-007）。
            // edit 返回的是该次变换后的全量状态，慢速落盘时可能与之后的内存写入竞争，
            // 用旧状态覆盖内存导致"刚保存的设置读回旧值"。内存快照由 write() 同步维护。
            runCatching {
                dataStore.edit(block)
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * 将既有 DataStore 值种子化到启动缓存（ARCH-007 一次性迁移）：
     * 老版本用户的语言/外观只存在 DataStore 中，首次启动补充到 SharedPreferences，
     * 使启动期路径（attachBaseContext / applyAppearance）无需读 DataStore 即可拿到真实值。
     */
    private fun seedStartupCache(loaded: Preferences) {
        val editor = startupPrefs.edit()
        var dirty = false
        if (startupPrefs.getString(KEY_STARTUP_LANGUAGE, null) == null) {
            loaded[KEY_LANGUAGE]?.let {
                editor.putString(KEY_STARTUP_LANGUAGE, it)
                dirty = true
            }
        }
        if (startupPrefs.getString(KEY_STARTUP_APPEARANCE, null) == null) {
            loaded[KEY_APPEARANCE]?.let {
                editor.putString(KEY_STARTUP_APPEARANCE, it)
                dirty = true
            }
        }
        if (startupPrefs.getString(KEY_STARTUP_TAB_STARTUP, null) == null) {
            loaded[KEY_STARTUP_TAB]?.let {
                editor.putString(KEY_STARTUP_TAB_STARTUP, it)
                dirty = true
            }
        }
        if (dirty) editor.apply()
    }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
        private val KEY_DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        private val KEY_DOWNLOAD_LOCATION_NAME = stringPreferencesKey("download_location_name")
        private val KEY_AUTO_CLEAN_CACHE = booleanPreferencesKey("auto_clean_cache")
        private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        private val KEY_HIDE_BOOKMARKED_CREATORS = booleanPreferencesKey("hide_bookmarked_creators")
        private val KEY_DEDUPE_BY_CREATOR = booleanPreferencesKey("dedupe_by_creator")
        private val KEY_AUTO_SUBSCRIBE_ON_BOOKMARK = booleanPreferencesKey("auto_subscribe_on_bookmark")
        private val KEY_LAST_CACHE_CLEAN_TIME = longPreferencesKey("last_cache_clean_time")
        private val KEY_CACHE_THRESHOLD_BYTES = longPreferencesKey("cache_threshold_bytes")
        private val KEY_STARTUP_TAB = stringPreferencesKey("startup_tab")

        // 启动缓存（SharedPreferences）key（ARCH-007）
        private const val KEY_STARTUP_LANGUAGE = "startup_language"
        private const val KEY_STARTUP_APPEARANCE = "startup_appearance"
        private const val KEY_STARTUP_TAB_STARTUP = "startup_tab"

        // 默认缓存阈值：200MB（FRONTEND-003）
        private const val DEFAULT_CACHE_THRESHOLD_BYTES = 200L * 1024 * 1024

        /**
         * 计算应用缓存目录总大小（cacheDir + externalCacheDir）。
         * 纯工具方法，保留为 companion 静态方法以便外部无实例时调用。
         */
        fun getCacheSize(context: Context): Long {
            return try {
                // 同时统计 cacheDir 与 externalCacheDir，反映真实占用（P2）
                var size = getDirSize(context.cacheDir)
                context.externalCacheDir?.let { size += getDirSize(it) }
                size
            } catch (_: Exception) {
                0L
            }
        }

        private fun getDirSize(dir: java.io.File): Long {
            var size = 0L
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    size += if (file.isDirectory) getDirSize(file) else file.length()
                }
            }
            return size
        }

        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))} MB"
                else -> "${String.format("%.1f", bytes / (1024.0 * 1024.0 * 1024.0))} GB"
            }
        }
    }
}
