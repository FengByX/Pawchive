package com.pawchive.data

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

class SettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

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

    fun getLanguage(): Language {
        val code = prefs.getString("language", "zh") ?: "zh"
        return Language.entries.find { it.code == code } ?: Language.CHINESE
    }

    fun setLanguage(language: Language) {
        prefs.edit().putString("language", language.code).apply()
    }

    fun getAppearance(): Appearance {
        val name = prefs.getString("appearance", Appearance.FOLLOW_SYSTEM.name) ?: Appearance.FOLLOW_SYSTEM.name
        return try { Appearance.valueOf(name) } catch (_: Exception) { Appearance.FOLLOW_SYSTEM }
    }

    fun setAppearance(appearance: Appearance) {
        prefs.edit().putString("appearance", appearance.name).apply()
        AppCompatDelegate.setDefaultNightMode(appearance.mode)
    }

    fun getDownloadTreeUri(): Uri? {
        val uriString = prefs.getString("download_tree_uri", null) ?: return null
        return try { Uri.parse(uriString) } catch (_: Exception) { null }
    }

    fun setDownloadTreeUri(uri: Uri?, displayName: String) {
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString("download_tree_uri", uri.toString())
            editor.putString("download_location_name", displayName)
        } else {
            editor.remove("download_tree_uri")
            editor.remove("download_location_name")
        }
        editor.apply()
    }

    fun getDownloadLocationName(): String {
        return prefs.getString("download_location_name", "") ?: ""
    }

    fun isAutoCleanCacheEnabled(): Boolean {
        return prefs.getBoolean("auto_clean_cache", false)
    }

    fun setAutoCleanCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_clean_cache", enabled).apply()
    }

    companion object {
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
