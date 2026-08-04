package com.pawchive.data

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 顶层 DataStore 单例（DataStore 必须是单例，每个文件名只允许一个实例）
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class SettingsManager private constructor(context: Context) {

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

    private val dataStore = context.appSettingsDataStore

    // 内存缓存快照：首次访问时同步加载（带异常保护），后续读取零开销。
    // 必须同步加载，否则启动期 attachBaseContext / applyAppearance 会读到空缓存，
    // 导致语言、外观等设置项全部失效（回到默认值）。
    @Volatile
    private var settingsCache: Preferences = loadInitialCache()

    private fun loadInitialCache(): Preferences {
        return try {
            runBlocking { dataStore.data.first() }
        } catch (_: Exception) {
            // DataStore 文件损坏/磁盘异常时降级为空缓存，避免启动崩溃
            emptyPreferences()
        }
    }

    fun getLanguage(): Language {
        val code = read { it[KEY_LANGUAGE] } ?: "zh"
        return Language.entries.find { it.code == code } ?: Language.CHINESE
    }

    fun setLanguage(language: Language) {
        write { it[KEY_LANGUAGE] = language.code }
    }

    fun getAppearance(): Appearance {
        val name = read { it[KEY_APPEARANCE] } ?: Appearance.FOLLOW_SYSTEM.name
        return try { Appearance.valueOf(name) } catch (_: Exception) { Appearance.FOLLOW_SYSTEM }
    }

    fun setAppearance(appearance: Appearance) {
        write { it[KEY_APPEARANCE] = appearance.name }
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
     * 同步读取内存缓存快照（无磁盘 I/O、无 runBlocking）。
     */
    private fun <T> read(block: (Preferences) -> T): T = block(settingsCache)

    /**
     * 异步写入：更新内存缓存快照 + 在 IO 线程落盘（带异常处理）。
     * 写入后立即更新内存缓存，保证后续同步读取立即可见。
     */
    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        settingsCache = settingsCache.toMutablePreferences().apply { block(this) }
        ioScope.launch {
            runCatching {
                val updated = dataStore.edit(block)
                settingsCache = updated
            }.onFailure { it.printStackTrace() }
        }
    }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
        private val KEY_DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        private val KEY_DOWNLOAD_LOCATION_NAME = stringPreferencesKey("download_location_name")
        private val KEY_AUTO_CLEAN_CACHE = booleanPreferencesKey("auto_clean_cache")
        private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }

        fun applyAppearance(context: Context) {
            val manager = getInstance(context)
            AppCompatDelegate.setDefaultNightMode(manager.getAppearance().mode)
        }

        fun getCacheSize(context: Context): Long {
            return try {
                val cacheDir = context.cacheDir
                getDirSize(cacheDir)
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
