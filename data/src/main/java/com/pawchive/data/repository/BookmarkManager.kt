package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.pawchive.core.model.Post
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// 顶层 DataStore 单例
private val Context.bookmarksDataStore: DataStore<Preferences> by preferencesDataStore(name = "pawchive_bookmarks")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常（P1）
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 收藏管理器（ARCH-003：已迁移至 Hilt 构造函数注入）。
 * 由 @Singleton + @Inject constructor 管理 lifecycle，移除 getInstance() 单例入口。
 *
 * ARCH-FEATURE-001：收藏/取消收藏/清空时同步维护离线归档索引（[OfflineArchiveRepository]），
 * 为离线浏览与全文搜索提供数据。
 */
@Singleton
class BookmarkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offlineArchiveRepository: OfflineArchiveRepository
) {

    private val dataStore = context.bookmarksDataStore
    private val gson = Gson()
    private val writeMutex = Mutex()
    private val loaded = AtomicBoolean(false)

    // 内存缓存快照：初始为空，构造后在 IO 线程异步加载，避免主线程阻塞（P1）
    @Volatile
    private var cache: Preferences = preferencesOf()

    init {
        ioScope.launch { runCatching { loadCache() }.onFailure { it.printStackTrace() } }
    }

    // 确保缓存已从磁盘加载；仅在未加载时短暂阻塞（IO 线程），
    // 避免基于空缓存计算并写入导致旧数据丢失。
    private fun ensureLoaded() {
        if (!loaded.get()) {
            runBlocking(Dispatchers.IO) { loadCache() }
        }
    }

    private suspend fun loadCache() {
        writeMutex.withLock {
            if (!loaded.get()) {
                migrateFromSharedPreferencesIfNeeded(context)
                cache = dataStore.data.first()
                loaded.set(true)
            }
        }
    }

    private val orderedPostKeysKey = stringPreferencesKey("ordered_post_object_keys")
    private val separator = "|"

    fun bookmarkPost(post: Post) {
        ensureLoaded()
        val objectKey = getPostObjectKey(post.service, post.user, post.id)
        editSync { prefs ->
            prefs[getPostKey(post.service, post.user, post.id)] = true
            prefs[objectKey] = gson.toJson(post)
        }
        appendOrderedKey(objectKey.name)
        // ARCH-FEATURE-001：异步同步离线归档索引（失败仅记日志，不阻塞收藏主流程）
        ioScope.launch {
            runCatching { offlineArchiveRepository.index(post) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun unbookmarkPost(service: String, creatorId: String, postId: String) {
        ensureLoaded()
        val objectKey = getPostObjectKey(service, creatorId, postId)
        editSync { prefs ->
            prefs.remove(getPostKey(service, creatorId, postId))
            prefs.remove(objectKey)
        }
        removeFromOrdered(objectKey.name)
        // ARCH-FEATURE-001：同步移除离线归档索引
        ioScope.launch {
            runCatching { offlineArchiveRepository.remove(service, creatorId, postId) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun isPostBookmarked(service: String, creatorId: String, postId: String): Boolean {
        return cache[getPostKey(service, creatorId, postId)] ?: false
    }

    /**
     * 返回所有已收藏的 Post，顺序为添加顺序（最早的在前）。
     * 对于历史数据（无顺序记录的旧收藏），自动迁移追加到末尾。
     */
    fun getBookmarkedPosts(): List<Post> {
        ensureLoaded()
        val orderedKeys = getOrderedKeys().toMutableList()

        // 迁移：把缓存中存在但没有顺序记录的旧 post_object_ key 补进列表末尾
        val orphanKeys = cache.asMap().keys
            .map { it.name }
            .filter { it.startsWith("post_object_") }
            .filter { it !in orderedKeys }
        if (orphanKeys.isNotEmpty()) {
            orderedKeys.addAll(orphanKeys)
            saveOrderedKeys(orderedKeys)
        }

        val list = mutableListOf<Post>()
        for (key in orderedKeys) {
            val json = cache[stringPreferencesKey(key)] ?: continue
            try {
                list.add(gson.fromJson(json, Post::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return list
    }

    /**
     * 在本地收藏中搜索（FEATURE-002 离线搜索）。
     * 匹配标题和创作者名称（不区分大小写）。
     */
    fun searchBookmarkedPosts(query: String): List<Post> {
        if (query.isBlank()) return getBookmarkedPosts()
        val lowerQuery = query.lowercase().trim()
        return getBookmarkedPosts().filter { post ->
            post.title?.lowercase()?.contains(lowerQuery) == true ||
                post.userName?.lowercase()?.contains(lowerQuery) == true ||
                post.user.lowercase().contains(lowerQuery) ||
                post.content?.lowercase()?.contains(lowerQuery) == true
        }
    }

    /**
     * 获取单个已收藏的 Post（FEATURE-002 离线阅读）。
     * 用于网络不可用时从本地缓存加载帖子详情。
     */
    fun getBookmarkedPost(service: String, creatorId: String, postId: String): Post? {
        ensureLoaded()
        val objectKey = getPostObjectKey(service, creatorId, postId)
        val json = cache[objectKey] ?: return null
        return try {
            gson.fromJson(json, Post::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun bookmarkCreator(service: String, creatorId: String) {
        ensureLoaded()
        editSync { it[getCreatorKey(service, creatorId)] = true }
    }

    fun unbookmarkCreator(service: String, creatorId: String) {
        ensureLoaded()
        editSync { it.remove(getCreatorKey(service, creatorId)) }
    }

    fun isCreatorBookmarked(service: String, creatorId: String): Boolean {
        return cache[getCreatorKey(service, creatorId)] ?: false
    }

    /**
     * 返回所有本地收藏的创作者（service, creatorId）集合（ARCH-FEATURE-006）。
     * 首页"隐藏已收藏作者的帖子"过滤用；键存在即视为收藏（unbookmark 会移除键）。
     */
    fun getBookmarkedCreators(): Set<Pair<String, String>> {
        ensureLoaded()
        return cache.asMap()
            .filterKeys { it.name.startsWith(CREATOR_KEY_PREFIX) }
            .filterValues { it is Boolean && it == true }
            .keys
            .mapNotNull { parseCreatorKey(it.name) }
            .toSet()
    }

    /** 解析 `creator_<service>_<creatorId>` 键；creatorId 可含下划线，取首个分隔符。 */
    private fun parseCreatorKey(key: String): Pair<String, String>? {
        val raw = key.removePrefix(CREATOR_KEY_PREFIX)
        val index = raw.indexOf('_')
        if (index <= 0 || index == raw.lastIndex) return null
        return raw.substring(0, index) to raw.substring(index + 1)
    }

    /**
     * 清除所有本地收藏数据（FEATURE-003 账号切换/登出时数据隔离）。
     * 同步清空内存缓存并异步落盘；同时清空离线归档索引（ARCH-FEATURE-001）。
     */
    fun clearAllForAccountSwitch() {
        ensureLoaded()
        editSync { prefs -> prefs.clear() }
        ioScope.launch {
            runCatching { offlineArchiveRepository.clearAll() }
                .onFailure { it.printStackTrace() }
        }
    }

    /**
     * 批量导入收藏（ARCH-FEATURE-005 备份恢复）。
     * 清空现有收藏后单次 DataStore 写入全部帖子/创作者（保留迁移完成标记，
     * 避免旧 SharedPreferences 再次迁移）；随后逐条重建离线归档索引。
     */
    suspend fun importAll(posts: List<Post>, creators: List<Pair<String, String>>) {
        ensureLoaded()
        val updated = dataStore.edit { prefs ->
            prefs.clear()
            prefs[booleanPreferencesKey(MIGRATION_DONE_KEY)] = true
            prefs[orderedPostKeysKey] = posts
                .map { getPostObjectKey(it.service, it.user, it.id).name }
                .joinToString(separator)
            posts.forEach { post ->
                prefs[getPostKey(post.service, post.user, post.id)] = true
                prefs[getPostObjectKey(post.service, post.user, post.id)] = gson.toJson(post)
            }
            creators.forEach { (service, creatorId) ->
                prefs[getCreatorKey(service, creatorId)] = true
            }
        }
        cache = updated
        loaded.set(true)
        // 逐条重建离线归档索引（失败仅记日志，不阻塞导入主流程）
        for (post in posts) {
            runCatching { offlineArchiveRepository.index(post) }
                .onFailure { it.printStackTrace() }
        }
    }

    private fun getPostKey(service: String, creatorId: String, postId: String): Preferences.Key<Boolean> {
        return booleanPreferencesKey("post_${service}_${creatorId}_$postId")
    }

    private fun getPostObjectKey(service: String, creatorId: String, postId: String): Preferences.Key<String> {
        return stringPreferencesKey("post_object_${service}_${creatorId}_$postId")
    }

    private fun getCreatorKey(service: String, creatorId: String): Preferences.Key<Boolean> {
        return booleanPreferencesKey("creator_${service}_$creatorId")
    }

    private fun getOrderedKeys(): List<String> {
        val raw = cache[orderedPostKeysKey] ?: ""
        return raw.split(separator).filter { it.isNotEmpty() }
    }

    private fun saveOrderedKeys(keys: List<String>) {
        editSync { it[orderedPostKeysKey] = keys.joinToString(separator) }
    }

    private fun appendOrderedKey(key: String) {
        ensureLoaded()
        val current = getOrderedKeys()
        if (key !in current) {
            saveOrderedKeys(current + key)
        }
    }

    private fun removeFromOrdered(key: String) {
        ensureLoaded()
        val current = getOrderedKeys()
        if (key in current) {
            saveOrderedKeys(current - key)
        }
    }

    /**
     * 同步更新内存缓存（保证 UI 立即可见）+ 异步、串行、带异常处理地落盘（P1）。
     */
    private fun editSync(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // 先更新内存缓存（保证后续同步读取立即可见）
        cache = cache.toMutablePreferences().apply { block(this) }
        // 异步、串行、带异常处理的落盘
        ioScope.launch {
            runCatching {
                val updated = writeMutex.withLock { dataStore.edit(block) }
                // 以磁盘最新状态刷新内存快照，保持权威一致
                cache = updated
            }.onFailure { it.printStackTrace() }
        }
    }

    companion object {
        private const val MIGRATION_DONE_KEY = "datastore_migration_done"
        private const val OLD_PREFS_NAME = "pawchive_bookmarks"

        /** 创作者收藏键前缀（与 [BookmarkManager.getCreatorKey] 保持一致）。 */
        private const val CREATOR_KEY_PREFIX = "creator_"

        /**
         * 一次性迁移：把旧 SharedPreferences 数据导入 DataStore
         * 通过标记键避免重复迁移
         */
        private suspend fun migrateFromSharedPreferencesIfNeeded(context: Context) {
            val dataStore = context.bookmarksDataStore
            val current = dataStore.data.first()
            if (current[booleanPreferencesKey(MIGRATION_DONE_KEY)] == true) return

            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            if (oldPrefs.all.isEmpty()) {
                // 旧 SP 为空，直接标记完成
                dataStore.edit { it[booleanPreferencesKey(MIGRATION_DONE_KEY)] = true }
                return
            }

            dataStore.edit { prefs ->
                oldPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> prefs[booleanPreferencesKey(key)] = value
                        is String -> prefs[stringPreferencesKey(key)] = value
                        // 其它类型（Int/Long/Float/Set）收藏场景未使用，忽略
                    }
                }
                prefs[booleanPreferencesKey(MIGRATION_DONE_KEY)] = true
            }
            // 迁移完成后清理旧 SP 文件
            context.deleteSharedPreferences(OLD_PREFS_NAME)
        }
    }
}
