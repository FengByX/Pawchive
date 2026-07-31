package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.pawchive.data.model.Post
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 顶层 DataStore 单例
private val Context.bookmarksDataStore: DataStore<Preferences> by preferencesDataStore(name = "pawchive_bookmarks")

class BookmarkManager(context: Context) {

    private val dataStore = context.bookmarksDataStore
    private val gson = Gson()

    // 内存缓存快照：构造时一次性加载，后续读取零开销（适配器高频调用 isPostBookmarked）
    // 写入时同步更新缓存 + 异步落盘
    @Volatile
    private var cache: Preferences = runBlocking {
        // 首次访问时执行 SP → DataStore 一次性迁移，避免用户丢失已有收藏
        migrateFromSharedPreferencesIfNeeded(context)
        dataStore.data.first()
    }

    private val orderedPostKeysKey = stringPreferencesKey("ordered_post_object_keys")
    private val separator = "|"

    fun bookmarkPost(post: Post) {
        val objectKey = getPostObjectKey(post.service, post.user, post.id)
        editSync { prefs ->
            prefs[getPostKey(post.service, post.user, post.id)] = true
            prefs[objectKey] = gson.toJson(post)
        }
        appendOrderedKey(objectKey.name)
    }

    fun unbookmarkPost(service: String, creatorId: String, postId: String) {
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
        editSync { it[getCreatorKey(service, creatorId)] = true }
    }

    fun unbookmarkCreator(service: String, creatorId: String) {
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
        val current = getOrderedKeys()
        if (key !in current) {
            saveOrderedKeys(current + key)
        }
    }

    private fun removeFromOrdered(key: String) {
        val current = getOrderedKeys()
        if (key in current) {
            saveOrderedKeys(current - key)
        }
    }

    /**
     * 同步更新内存缓存 + 异步落盘到 DataStore
     */
    private fun editSync(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // 先更新内存缓存（保证后续同步读取立即可见）
        cache = cache.toMutablePreferences().apply { block(this) }
        // 异步落盘
        kotlinx.coroutines.GlobalScope.launch {
            dataStore.edit(block)
        }
    }

    companion object {
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
