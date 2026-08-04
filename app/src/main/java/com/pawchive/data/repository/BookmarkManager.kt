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
import com.pawchive.data.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

// 顶层 DataStore 单例
private val Context.bookmarksDataStore: DataStore<Preferences> by preferencesDataStore(name = "pawchive_bookmarks")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常（P1）
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class BookmarkManager private constructor(private val context: Context) {

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
    }

    fun unbookmarkPost(service: String, creatorId: String, postId: String) {
        ensureLoaded()
        val objectKey = getPostObjectKey(service, creatorId, postId)
        editSync { prefs ->
            prefs.remove(getPostKey(service, creatorId, postId))
            prefs.remove(objectKey)
        }
        removeFromOrdered(objectKey.name)
    }

    fun isPostBookmarked(service: String, creatorId: String, postId: String): Boolean {
        return cache[getPostKey(service, creatorId, postId)] ?: false
    }

    /**
     * 返回所有已收藏的 Post，顺序为添加顺序（最早的在前）。
     * 对于历史数据（无顺序记录的旧收藏），自动迁移追加到末尾。
     */
    fun getBookmarkedPosts(): List<Post> {
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
        @Volatile
        private var instance: BookmarkManager? = null

        /**
         * 全局单例：所有画面共享同一份内存缓存与串行写锁，
         * 避免多实例各自维护缓存导致的跨屏状态不一致与并发写覆盖（跨屏串号 fix）。
         */
        fun getInstance(context: Context): BookmarkManager =
            instance ?: synchronized(this) {
                instance ?: BookmarkManager(context.applicationContext).also { instance = it }
            }

        private const val MIGRATION_DONE_KEY = "datastore_migration_done"
        private const val OLD_PREFS_NAME = "pawchive_bookmarks"

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
