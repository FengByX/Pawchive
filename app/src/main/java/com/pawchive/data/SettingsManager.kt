package com.pawchive.data

import android.content.Context
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

    companion object {
        fun applyAppearance(context: Context) {
            val manager = SettingsManager(context)
            AppCompatDelegate.setDefaultNightMode(manager.getAppearance().mode)
        }
    }
}
