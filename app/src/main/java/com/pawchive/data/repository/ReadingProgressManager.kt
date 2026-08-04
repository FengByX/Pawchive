package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_progress")

private val progressIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 阅读进度与视频播放位置管理器（FEATURE-005）。
 *
 * - 视频播放位置：按 URL 持久化，App 重启后可恢复至上次播放位置
 * - 阅读滚动位置：按帖子 ID 持久化，再次打开时恢复滚动位置
 */
class ReadingProgressManager private constructor(context: Context) {

    private val dataStore = context.readingProgressDataStore

    @Volatile
    private var cache: Preferences = loadInitialCache()

    private fun loadInitialCache(): Preferences {
        return try {
            runBlocking {
                withTimeoutOrNull(500L) { dataStore.data.first() } ?: androidx.datastore.preferences.core.emptyPreferences()
            }
        } catch (_: Exception) {
            androidx.datastore.preferences.core.emptyPreferences()
        }
    }

    /**
     * 保存视频播放位置（FEATURE-005 视频记忆）。
     */
    fun saveVideoPosition(url: String, positionMs: Long) {
        if (url.isBlank() || positionMs <= 0) return
        val key = longPreferencesKey("video_${url.hashCode()}")
        updateCache { it[longPreferencesKey("video_${url.hashCode()}")] = positionMs }
        progressIoScope.launch {
            runCatching { dataStore.edit { it[key] = positionMs } }
        }
    }

    /**
     * 获取视频播放位置（FEATURE-005 视频记忆）。
     * 返回 0 表示无记录。
     */
    fun getVideoPosition(url: String): Long {
        val key = longPreferencesKey("video_${url.hashCode()}")
        return cache[key] ?: 0L
    }

    /**
     * 保存帖子阅读滚动位置（FEATURE-005 阅读进度）。
     */
    fun saveReadingScroll(postId: String, scrollY: Int) {
        if (postId.isBlank()) return
        val key = longPreferencesKey("scroll_$postId")
        updateCache { it[key] = scrollY.toLong() }
        progressIoScope.launch {
            runCatching { dataStore.edit { it[key] = scrollY.toLong() } }
        }
    }

    /**
     * 获取帖子阅读滚动位置（FEATURE-005 阅读进度）。
     * 返回 0 表示无记录。
     */
    fun getReadingScroll(postId: String): Int {
        val key = longPreferencesKey("scroll_$postId")
        return (cache[key] ?: 0L).toInt()
    }

    /**
     * 清除指定帖子的阅读进度。
     */
    fun clearReadingScroll(postId: String) {
        val key = longPreferencesKey("scroll_$postId")
        updateCache { it.remove(key) }
        progressIoScope.launch {
            runCatching { dataStore.edit { it.remove(key) } }
        }
    }

    private fun updateCache(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        cache = cache.toMutablePreferences().apply { block(this) }
    }

    companion object {
        @Volatile
        private var instance: ReadingProgressManager? = null

        fun getInstance(context: Context): ReadingProgressManager =
            instance ?: synchronized(this) {
                instance ?: ReadingProgressManager(context.applicationContext).also { instance = it }
            }
    }
}
