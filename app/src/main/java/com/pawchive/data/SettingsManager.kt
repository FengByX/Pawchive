package com.pawchive.data

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 顶层 DataStore 单例（DataStore 必须是单例，每个文件名只允许一个实例）
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class SettingsManager(private val context: Context) {

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

    /**
     * 同步读取（仅在首次访问时阻塞，DataStore 内部会缓存到内存）
     * 后续读取直接从 DataStore 内存缓存返回，无磁盘 I/O。
     */
    private fun <T> read(block: (Preferences) -> T): T = runBlocking {
        block(dataStore.data.first())
    }

    /**
     * 异步写入（同步返回，DataStore 在后台协程执行磁盘 I/O）
     */
    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        kotlinx.coroutines.GlobalScope.launch {
            dataStore.edit(block)
        }
    }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
        private val KEY_DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        private val KEY_DOWNLOAD_LOCATION_NAME = stringPreferencesKey("download_location_name")
        private val KEY_AUTO_CLEAN_CACHE = booleanPreferencesKey("auto_clean_cache")

        fun applyAppearance(context: Context) {
            val manager = SettingsManager(context)
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
