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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        // SYSTEM：不覆盖系统语言。code 为空串，setLanguage 时不写 AppCompat locale（见下）。
        SYSTEM("跟随系统", ""),
        CHINESE("中文", "zh"),
        ENGLISH("English", "en"),
        JAPANESE("日本語", "ja");

        companion object {
            /** 按代码查找；code 为空/未知时返回 null（调用方决定回退语义，避免吞掉 SYSTEM）。 */
            fun fromCode(code: String?): Language? = entries.find { it.code == code }
        }
    }

    enum class Appearance(val displayName: String, val mode: Int) {
        LIGHT("日间模式", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("夜间模式", AppCompatDelegate.MODE_NIGHT_YES),
        FOLLOW_SYSTEM("跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    /**
     * 更新检查频率（FEATURE：设置项扩展批次一）。
     * 仅在"自动检查更新"总开关开启时生效。
     */
    enum class UpdateFrequency(val key: String) {
        EVERY_LAUNCH("every_launch"),
        DAILY("daily"),
        WEEKLY("weekly");

        companion object {
            fun fromKey(key: String?): UpdateFrequency =
                entries.find { it.key == key } ?: DAILY
        }
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

    /**
     * 下载文件命名格式（FEATURE：设置项扩展批次三）。
     * - ORIGINAL：保留原始文件名；
     * - TIMESTAMP：旧版默认行为 `Pawchive_时间戳_原名尾`；
     * - TITLE_TIME：`标题_日期时间.ext`（标题缺失时回退原名）。
     */
    enum class FilenameFormat(val key: String) {
        ORIGINAL("original"),
        TIMESTAMP("timestamp"),
        TITLE_TIME("title_time");

        companion object {
            fun fromKey(key: String?): FilenameFormat =
                entries.find { it.key == key } ?: TIMESTAMP
        }
    }

    /**
     * 更新通道（FEATURE：设置项扩展批次三）。
     * - STABLE：仅正式版（GitHub releases/latest 语义）；
     * - BETA：包含预发布版本（取语义化版本号最新的一条，含 beta/rc）。
     */
    enum class UpdateChannel(val key: String) {
        STABLE("stable"),
        BETA("beta");

        companion object {
            fun fromKey(key: String?): UpdateChannel =
                entries.find { it.key == key } ?: STABLE
        }
    }

    /**
     * 主题强调色（FEATURE：设置项扩展批次三）。
     * 对应 values/themes.xml 中预置的 6 套 Theme.Pawchive.* 主题。
     */
    enum class AccentTheme(val key: String) {
        DEFAULT("default"),
        VIOLET("violet"),
        OCEAN("ocean"),
        FOREST("forest"),
        SUNSET("sunset"),
        ROSE("rose");

        companion object {
            fun fromKey(key: String?): AccentTheme =
                entries.find { it.key == key } ?: DEFAULT
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
        return Language.fromCode(code) ?: Language.CHINESE
    }

    /**
     * 启动期语言读取（ARCH-007）：仅读轻量 SharedPreferences，无 DataStore、无阻塞。
     * 仅供 MainActivity.attachBaseContext 等启动期路径使用；常规读取走 [getLanguage]。
     */
    fun getStartupLanguage(): Language {
        val code = startupPrefs.getString(KEY_STARTUP_LANGUAGE, null)
            ?: read { it[KEY_LANGUAGE] }
            ?: "zh"
        return Language.fromCode(code) ?: Language.CHINESE
    }

    fun setLanguage(language: Language) {
        write { it[KEY_LANGUAGE] = language.code }
        startupPrefs.edit().putString(KEY_STARTUP_LANGUAGE, language.code).apply()
        // ARCH-007：同步到 AndroidX AppCompat locale 持久化机制（autoStoreLocales 元数据开启后
        // AppCompat 自行持久化并在 attachBaseContext 自动应用）。失败不影响已落盘的
        // DataStore + startupPrefs（手动 attachBaseContext 兜底），仅记录日志。
        runCatching {
            when (language) {
                // "跟随系统"：清空 AppCompat locale 覆盖，交还系统语言
                Language.SYSTEM -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                else -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.code))
            }
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

    // ---------- 设置项扩展（批次一：通知 / 播放 / 信息流 / 更新 / 隐私） ----------

    /** 下载完成/失败/暂停的终态通知（默认开；进度通知不受此项控制）。 */
    fun isDownloadCompleteNotificationEnabled(): Boolean {
        return read { it[KEY_DOWNLOAD_COMPLETE_NOTIFICATION] } ?: true
    }

    fun setDownloadCompleteNotificationEnabled(enabled: Boolean) {
        write { it[KEY_DOWNLOAD_COMPLETE_NOTIFICATION] = enabled }
    }

    /** 断点续播：按 URL 记忆视频播放位置，重进详情页时恢复（默认开）。 */
    fun isVideoResumeEnabled(): Boolean {
        return read { it[KEY_VIDEO_RESUME] } ?: true
    }

    fun setVideoResumeEnabled(enabled: Boolean) {
        write { it[KEY_VIDEO_RESUME] = enabled }
    }

    /** 新帖系统通知开关（默认开；关闭后同步仍在后台进行，仅不弹通知）。 */
    fun isNewPostNotificationEnabled(): Boolean {
        return read { it[KEY_NEW_POST_NOTIFICATION] } ?: true
    }

    fun setNewPostNotificationEnabled(enabled: Boolean) {
        write { it[KEY_NEW_POST_NOTIFICATION] = enabled }
    }

    /** 新帖后台同步周期（分钟）。WorkManager 周期任务下限为 15 分钟。 */
    fun getSyncIntervalMinutes(): Int {
        return (read { it[KEY_SYNC_INTERVAL_MINUTES] } ?: DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceAtLeast(MIN_SYNC_INTERVAL_MINUTES)
    }

    fun setSyncIntervalMinutes(minutes: Int) {
        write { it[KEY_SYNC_INTERVAL_MINUTES] = minutes.coerceAtLeast(MIN_SYNC_INTERVAL_MINUTES) }
    }

    /** 记住上次播放倍速：开启时新播放器以"上次倍速"起步（默认开）。 */
    fun isRememberPlaybackSpeedEnabled(): Boolean {
        return read { it[KEY_REMEMBER_PLAYBACK_SPEED] } ?: true
    }

    fun setRememberPlaybackSpeedEnabled(enabled: Boolean) {
        write { it[KEY_REMEMBER_PLAYBACK_SPEED] = enabled }
    }

    /** 上次使用的播放倍速（0.5x–2.0x 档位区间内的任意值）。 */
    fun getLastPlaybackSpeed(): Float {
        return (read { it[KEY_LAST_PLAYBACK_SPEED] } ?: DEFAULT_PLAYBACK_SPEED)
            .coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    }

    fun setLastPlaybackSpeed(speed: Float) {
        write { it[KEY_LAST_PLAYBACK_SPEED] = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED) }
    }

    /**
     * 首页默认排序（存 feature-home 的 HomePostSortOption.name）。
     * 返回 null 表示未设置过（首页回退到其自身默认值）。
     * core 层不依赖 feature 模块，故以字符串传递。
     */
    fun getHomeDefaultSort(): String? {
        return read { it[KEY_HOME_DEFAULT_SORT] }
    }

    fun setHomeDefaultSort(sortKey: String) {
        write { it[KEY_HOME_DEFAULT_SORT] = sortKey }
    }

    /** 搜索历史保留条数（上限钳制，默认 30，与历史行为一致）。 */
    fun getSearchHistoryLimit(): Int {
        return (read { it[KEY_SEARCH_HISTORY_LIMIT] } ?: DEFAULT_SEARCH_HISTORY_LIMIT)
            .coerceIn(MIN_SEARCH_HISTORY_LIMIT, MAX_SEARCH_HISTORY_LIMIT)
    }

    fun setSearchHistoryLimit(limit: Int) {
        write { it[KEY_SEARCH_HISTORY_LIMIT] = limit.coerceIn(MIN_SEARCH_HISTORY_LIMIT, MAX_SEARCH_HISTORY_LIMIT) }
    }

    /** 更新检查频率（仅在自动检查更新开启时生效）。 */
    fun getUpdateFrequency(): UpdateFrequency {
        return UpdateFrequency.fromKey(read { it[KEY_UPDATE_FREQUENCY] })
    }

    fun setUpdateFrequency(frequency: UpdateFrequency) {
        write { it[KEY_UPDATE_FREQUENCY] = frequency.key }
    }

    /** 列表加载原图（默认关＝省流量：缩略图 CDN 优先，失败回退原图）。 */
    fun isListOriginalImageEnabled(): Boolean {
        return read { it[KEY_LIST_ORIGINAL_IMAGE] } ?: false
    }

    fun setListOriginalImageEnabled(enabled: Boolean) {
        write { it[KEY_LIST_ORIGINAL_IMAGE] = enabled }
    }

    // ---------- 设置项扩展（批次二：下载参数） ----------

    /**
     * 最大并发下载数（1–5，默认 5）。
     * 【合规约束】文件服务器运维方要求同时下载不超过 5 个任务（见 DownloadCenter 注释），
     * 上限钳制在 5，禁止放开，否则可能被限流或封禁。
     */
    fun getMaxConcurrentDownloads(): Int {
        return (read { it[KEY_MAX_CONCURRENT_DOWNLOADS] } ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS_LIMIT)
    }

    fun setMaxConcurrentDownloads(count: Int) {
        write { it[KEY_MAX_CONCURRENT_DOWNLOADS] = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS_LIMIT) }
    }

    /**
     * 单条 HTTP 请求的自动重试次数（1–3，默认 3；1 = 不自动重试）。
     * 重试间隔仍强制 ≥1 秒线性退避（文件服务器运维方要求）。
     */
    fun getDownloadMaxRetry(): Int {
        return (read { it[KEY_DOWNLOAD_MAX_RETRY] } ?: DEFAULT_DOWNLOAD_MAX_RETRY)
            .coerceIn(MIN_DOWNLOAD_RETRY, MAX_DOWNLOAD_RETRY)
    }

    fun setDownloadMaxRetry(count: Int) {
        write { it[KEY_DOWNLOAD_MAX_RETRY] = count.coerceIn(MIN_DOWNLOAD_RETRY, MAX_DOWNLOAD_RETRY) }
    }

    /** 仅 Wi-Fi 下载（默认关）。开启后无 Wi-Fi 时任务保持等待，网络就绪自动继续。 */
    fun isWifiOnlyDownloadEnabled(): Boolean {
        return read { it[KEY_WIFI_ONLY_DOWNLOAD] } ?: false
    }

    fun setWifiOnlyDownloadEnabled(enabled: Boolean) {
        write { it[KEY_WIFI_ONLY_DOWNLOAD] = enabled }
    }

    // ---------- 设置项扩展（批次三：命名格式 / 更新通道 / 外观增强 / 隐私 / 自动备份） ----------

    /** 下载文件命名格式（默认时间戳命名＝旧版行为）。 */
    fun getFilenameFormat(): FilenameFormat {
        return FilenameFormat.fromKey(read { it[KEY_FILENAME_FORMAT] })
    }

    fun setFilenameFormat(format: FilenameFormat) {
        write { it[KEY_FILENAME_FORMAT] = format.key }
    }

    /** 更新通道（默认稳定版）。 */
    fun getUpdateChannel(): UpdateChannel {
        return UpdateChannel.fromKey(read { it[KEY_UPDATE_CHANNEL] })
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        write { it[KEY_UPDATE_CHANNEL] = channel.key }
    }

    /** 主题强调色（默认 Pawchive 蓝）。 */
    fun getAccentTheme(): AccentTheme {
        return AccentTheme.fromKey(read { it[KEY_ACCENT_THEME] })
    }

    fun setAccentTheme(theme: AccentTheme) {
        write { it[KEY_ACCENT_THEME] = theme.key }
        startupPrefs.edit().putString(KEY_STARTUP_ACCENT, theme.key).apply()
    }

    /**
     * 启动期主题强调色读取（ARCH-007 模式）：仅读轻量 SharedPreferences，无阻塞。
     * 仅供 MainActivity.onCreate 的 setTheme 使用；常规读取走 [getAccentTheme]。
     */
    fun getStartupAccentTheme(): AccentTheme {
        val key = startupPrefs.getString(KEY_STARTUP_ACCENT, null)
            ?: read { it[KEY_ACCENT_THEME] }
        return AccentTheme.fromKey(key)
    }

    /** 减少动画：关闭骨架屏 shimmer、缩略图交叉淡入与窗口过渡动画。 */
    fun isReduceAnimationsEnabled(): Boolean {
        return read { it[KEY_REDUCE_ANIMATIONS] } ?: false
    }

    fun setReduceAnimationsEnabled(enabled: Boolean) {
        write { it[KEY_REDUCE_ANIMATIONS] = enabled }
    }

    /**
     * 列表网格列数（1–3，默认 1＝单列列表，与旧行为一致）。
     * 2/3 时首页与搜索结果切换为 GridLayoutManager。
     */
    fun getGridColumns(): Int {
        return (read { it[KEY_GRID_COLUMNS] } ?: DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
    }

    fun setGridColumns(columns: Int) {
        write { it[KEY_GRID_COLUMNS] = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS) }
    }

    /** 定时自动备份开关（默认关；开启后每天自动导出备份到所选目录）。 */
    fun isAutoBackupEnabled(): Boolean {
        return read { it[KEY_AUTO_BACKUP_ENABLED] } ?: false
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        write { it[KEY_AUTO_BACKUP_ENABLED] = enabled }
    }

    /** 自动备份目标目录（SAF tree URI；设备绑定项，不随设置导出）。 */
    fun getAutoBackupTreeUri(): Uri? {
        val uriString = read { it[KEY_AUTO_BACKUP_TREE_URI] } ?: return null
        return try { Uri.parse(uriString) } catch (_: Exception) { null }
    }

    fun setAutoBackupTreeUri(uri: Uri?, displayName: String) {
        write { prefs ->
            if (uri != null) {
                prefs[KEY_AUTO_BACKUP_TREE_URI] = uri.toString()
                prefs[KEY_AUTO_BACKUP_LOCATION_NAME] = displayName
            } else {
                prefs.remove(KEY_AUTO_BACKUP_TREE_URI)
                prefs.remove(KEY_AUTO_BACKUP_LOCATION_NAME)
            }
        }
    }

    fun getAutoBackupLocationName(): String {
        return read { it[KEY_AUTO_BACKUP_LOCATION_NAME] } ?: ""
    }

    /** 上次自动备份成功的时间戳（毫秒）。0 表示从未备份。 */
    fun getLastAutoBackupTime(): Long {
        return read { it[KEY_LAST_AUTO_BACKUP_TIME] } ?: 0L
    }

    fun setLastAutoBackupTime(timestamp: Long) {
        write { it[KEY_LAST_AUTO_BACKUP_TIME] = timestamp }
    }

    /**
     * 清空全部设置（FEATURE：清除本地全部数据）。
     * 仅在设置页"清除本地全部数据"二次确认后调用；调用方负责重启应用使 UI 复位。
     */
    suspend fun resetAllSettings() {
        synchronized(cacheLock) {
            settingsCache = emptyPreferences()
            userWrote = false
        }
        runCatching { dataStore.edit { it.clear() } }
            .onFailure { it.printStackTrace() }
        startupPrefs.edit().clear().apply()
    }

    // ---------- 显示与缩放（FEATURE：UI 缩放 / 字号调节） ----------

    /**
     * 整体 UI 缩放比例（1.0 = 100%）。
     * 应用方式：MainActivity.attachBaseContext 覆盖 Configuration.densityDpi，
     * dp 与 sp 等比缩放（等同系统"显示大小"机制），文本与容器同步缩放，布局不错位。
     */
    fun getUiScale(): Float {
        return (read { it[KEY_UI_SCALE] } ?: DEFAULT_SCALE)
            .coerceIn(SCALE_MIN, SCALE_MAX)
    }

    fun setUiScale(scale: Float) {
        val clamped = scale.coerceIn(SCALE_MIN, SCALE_MAX)
        write { it[KEY_UI_SCALE] = clamped }
        startupPrefs.edit().putFloat(KEY_STARTUP_UI_SCALE, clamped).apply()
    }

    /** 全局文字大小比例（1.0 = 100%，乘以系统 fontScale 后叠加）。 */
    fun getFontScale(): Float {
        return (read { it[KEY_FONT_SCALE] } ?: DEFAULT_SCALE)
            .coerceIn(SCALE_MIN, SCALE_MAX)
    }

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(SCALE_MIN, SCALE_MAX)
        write { it[KEY_FONT_SCALE] = clamped }
        startupPrefs.edit().putFloat(KEY_STARTUP_FONT_SCALE, clamped).apply()
    }

    /**
     * 启动期 UI 缩放读取（ARCH-007 模式）：仅读轻量 SharedPreferences，无阻塞。
     * 仅供 MainActivity.attachBaseContext 使用；常规读取走 [getUiScale]。
     */
    fun getStartupUiScale(): Float {
        return startupPrefs.getFloat(KEY_STARTUP_UI_SCALE, DEFAULT_SCALE)
            .coerceIn(SCALE_MIN, SCALE_MAX)
    }

    /** 启动期文字缩放读取（ARCH-007 模式），语义同 [getStartupUiScale]。 */
    fun getStartupFontScale(): Float {
        return startupPrefs.getFloat(KEY_STARTUP_FONT_SCALE, DEFAULT_SCALE)
            .coerceIn(SCALE_MIN, SCALE_MAX)
    }

    /**
     * 上次缓存清理的时间戳（毫秒）。0 表示从未清理。
     * 用于 FRONTEND-003：展示上次清理时间，避免每次启动无条件清空。
     */    fun getLastCacheCleanTime(): Long {
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
        private val KEY_UI_SCALE = floatPreferencesKey("ui_scale")
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        private val KEY_LAST_CACHE_CLEAN_TIME = longPreferencesKey("last_cache_clean_time")
        private val KEY_CACHE_THRESHOLD_BYTES = longPreferencesKey("cache_threshold_bytes")
        private val KEY_STARTUP_TAB = stringPreferencesKey("startup_tab")

        // 启动缓存（SharedPreferences）key（ARCH-007）
        private const val KEY_STARTUP_LANGUAGE = "startup_language"
        private const val KEY_STARTUP_APPEARANCE = "startup_appearance"
        private const val KEY_STARTUP_TAB_STARTUP = "startup_tab"
        private const val KEY_STARTUP_UI_SCALE = "startup_ui_scale"
        private const val KEY_STARTUP_FONT_SCALE = "startup_font_scale"

        // 设置项扩展（批次一）
        private val KEY_DOWNLOAD_COMPLETE_NOTIFICATION =
            booleanPreferencesKey("download_complete_notification")
        private val KEY_VIDEO_RESUME = booleanPreferencesKey("video_resume")
        private val KEY_NEW_POST_NOTIFICATION = booleanPreferencesKey("new_post_notification")
        private val KEY_SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        private val KEY_REMEMBER_PLAYBACK_SPEED = booleanPreferencesKey("remember_playback_speed")
        private val KEY_LAST_PLAYBACK_SPEED = floatPreferencesKey("last_playback_speed")
        private val KEY_HOME_DEFAULT_SORT = stringPreferencesKey("home_default_sort")
        private val KEY_SEARCH_HISTORY_LIMIT = intPreferencesKey("search_history_limit")
        private val KEY_UPDATE_FREQUENCY = stringPreferencesKey("update_frequency")
        private val KEY_LIST_ORIGINAL_IMAGE = booleanPreferencesKey("list_original_image")
        private val KEY_MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        private val KEY_DOWNLOAD_MAX_RETRY = intPreferencesKey("download_max_retry")
        private val KEY_WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")

        // 设置项扩展（批次三）
        private val KEY_FILENAME_FORMAT = stringPreferencesKey("filename_format")
        private val KEY_UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        private val KEY_ACCENT_THEME = stringPreferencesKey("accent_theme")
        private val KEY_REDUCE_ANIMATIONS = booleanPreferencesKey("reduce_animations")
        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        private val KEY_AUTO_BACKUP_TREE_URI = stringPreferencesKey("auto_backup_tree_uri")
        private val KEY_AUTO_BACKUP_LOCATION_NAME = stringPreferencesKey("auto_backup_location_name")
        private val KEY_LAST_AUTO_BACKUP_TIME = longPreferencesKey("last_auto_backup_time")
        private const val KEY_STARTUP_ACCENT = "startup_accent_theme"

        // 批次三：网格列数钳制
        const val MIN_GRID_COLUMNS = 1
        const val MAX_GRID_COLUMNS = 3
        const val DEFAULT_GRID_COLUMNS = 1

        // 批次二：下载参数钳制
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS_LIMIT = 5
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 5
        const val MIN_DOWNLOAD_RETRY = 1
        const val MAX_DOWNLOAD_RETRY = 3
        const val DEFAULT_DOWNLOAD_MAX_RETRY = 3

        /** WorkManager 周期任务最小间隔（分钟），系统硬限制。 */
        const val MIN_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30

        // 播放倍速档位区间（与 PostDetailFragment.showSpeedDialog 的 speedValues 对应）
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
        const val DEFAULT_PLAYBACK_SPEED = 1.0f

        // 搜索历史保留条数钳制（默认 30 与 SearchHistoryManager 旧硬编码一致）
        const val MIN_SEARCH_HISTORY_LIMIT = 5
        const val MAX_SEARCH_HISTORY_LIMIT = 50
        const val DEFAULT_SEARCH_HISTORY_LIMIT = 30

        /** 缩放调节范围（UI 缩放与字号共用）：钳制在安全区间，避免极端值破坏布局。 */
        const val SCALE_MIN = 0.6f
        const val SCALE_MAX = 1.5f
        const val DEFAULT_SCALE = 1.0f

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
