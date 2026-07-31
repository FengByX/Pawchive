package com.pawchive.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 顶层 DataStore 单例
private val Context.blockedCreatorsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blocked_creators")

/**
 * 屏蔽创作者管理器（单例）。
 * 使用 DataStore 存储屏蔽列表，内存缓存快照实现同步高频读取。
 * 屏蔽键格式："blocked_service|creatorId"（与 BookmarkManager 一致）。
 */
class BlockedCreatorManager private constructor(context: Context) {

    private val dataStore = context.blockedCreatorsDataStore

    // 内存缓存快照：构造时一次性加载，后续读取零开销
    @Volatile
    private var cache: Preferences = runBlocking {
        dataStore.data.first()
    }

    /**
     * 判断指定创作者是否已被屏蔽
     */
    fun isCreatorBlocked(service: String, creatorId: String): Boolean {
        return cache[getBlockKey(service, creatorId)] ?: false
    }

    /**
     * 屏蔽指定创作者
     */
    fun blockCreator(service: String, creatorId: String) {
        editSync { it[getBlockKey(service, creatorId)] = true }
    }

    /**
     * 取消屏蔽指定创作者
     */
    fun unblockCreator(service: String, creatorId: String) {
        editSync { it.remove(getBlockKey(service, creatorId)) }
    }

    /**
     * 获取所有被屏蔽的创作者列表（service, creatorId）
     */
    fun getBlockedCreators(): List<Pair<String, String>> {
        return cache.asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) && it.value == true }
            .mapNotNull { entry ->
                val parts = entry.key.name.removePrefix(KEY_PREFIX).split("|")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
    }

    /**
     * 被屏蔽的创作者数量
     */
    fun getBlockedCount(): Int = getBlockedCreators().size

    private fun getBlockKey(service: String, creatorId: String): Preferences.Key<Boolean> {
        return booleanPreferencesKey("$KEY_PREFIX$service|$creatorId")
    }

    /**
     * 同步更新内存缓存 + 异步落盘
     */
    private fun editSync(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        cache = cache.toMutablePreferences().apply { block(this) }
        kotlinx.coroutines.GlobalScope.launch {
            dataStore.edit(block)
        }
    }

    companion object {
        private const val KEY_PREFIX = "blocked_"

        @Volatile
        private var instance: BlockedCreatorManager? = null

        fun getInstance(context: Context): BlockedCreatorManager {
            return instance ?: synchronized(this) {
                instance ?: BlockedCreatorManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
