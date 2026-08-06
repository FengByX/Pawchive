package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_progress")

private val progressIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 视频播放位置（ARCH-FEATURE-005 备份导出用）。 */
data class VideoPosition(val url: String, val positionMs: Long)

/** 帖子阅读滚动位置（ARCH-FEATURE-005 备份导出用）。 */
data class ScrollPosition(val postId: String, val scrollY: Int)

/** 阅读进度快照（ARCH-FEATURE-005 备份导出/导入用）。 */
data class ReadingProgressSnapshot(
    val videoPositions: List<VideoPosition> = emptyList(),
    val scrollPositions: List<ScrollPosition> = emptyList()
)

/**
 * 阅读进度与视频播放位置管理器（FEATURE-005；ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * - 视频播放位置：按 URL 持久化，App 重启后可恢复至上次播放位置
 * - 阅读滚动位置：按帖子 ID 持久化，再次打开时恢复滚动位置
 *
 * 视频进度磁盘键为 `video_<url.hashCode()>`（URL 不可逆），
 * 备份导出通过内存镜像 [videoPositions] 还原 URL（覆盖本会话记录过的视频）。
 */
@Singleton
class ReadingProgressManager @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.readingProgressDataStore

    @Volatile
    private var cache: Preferences = androidx.datastore.preferences.core.emptyPreferences()

    /** URL → 播放位置内存镜像（视频进度键不可逆，导出时据此还原 URL）。 */
    @Volatile
    private var videoPositions: Map<String, Long> = emptyMap()

    // PERF-006：移除构造时 runBlocking(500ms)，改为后台异步加载
    init {
        progressIoScope.launch {
            runCatching {
                cache = dataStore.data.first()
            }
        }
    }

    /**
     * 保存视频播放位置（FEATURE-005 视频记忆）。
     */
    fun saveVideoPosition(url: String, positionMs: Long) {
        if (url.isBlank() || positionMs <= 0) return
        videoPositions = videoPositions + (url to positionMs)
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

    // ---------- ARCH-FEATURE-005 备份导出/导入 ----------

    /**
     * 导出全部阅读进度：滚动位置完整导出（键含 postId）；
     * 视频位置取自内存镜像（仅覆盖本会话记录过的视频，键不可逆）。
     */
    suspend fun exportAll(): ReadingProgressSnapshot {
        val prefs = runCatching { dataStore.data.first() }
            .getOrDefault(androidx.datastore.preferences.core.emptyPreferences())
        val scrolls = prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name.startsWith(SCROLL_PREFIX)) {
                val postId = name.removePrefix(SCROLL_PREFIX)
                if (postId.isNotEmpty() && value is Long) ScrollPosition(postId, value.toInt())
                else null
            } else null
        }
        return ReadingProgressSnapshot(
            videoPositions = videoPositions.map { (url, ms) -> VideoPosition(url, ms) },
            scrollPositions = scrolls
        )
    }

    /** 导入阅读进度（覆盖现有数据）。 */
    suspend fun importAll(snapshot: ReadingProgressSnapshot) {
        videoPositions = emptyMap()
        runCatching { dataStore.edit { it.clear() } }
        cache = androidx.datastore.preferences.core.emptyPreferences()
        snapshot.videoPositions.forEach { saveVideoPosition(it.url, it.positionMs) }
        snapshot.scrollPositions.forEach { saveReadingScroll(it.postId, it.scrollY) }
    }

    private fun updateCache(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        cache = cache.toMutablePreferences().apply { block(this) }
    }

    private companion object {
        const val SCROLL_PREFIX = "scroll_"
    }
}
