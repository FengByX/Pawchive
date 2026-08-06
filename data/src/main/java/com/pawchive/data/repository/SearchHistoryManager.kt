package com.pawchive.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// 顶层 DataStore 单例
private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history_prefs")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常（P1）
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 搜索历史管理器（ARCH-003：已迁移至 Hilt 构造函数注入）。
 */
@Singleton
class SearchHistoryManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val dataStore = context.searchHistoryDataStore
    private val writeMutex = Mutex()
    private val loaded = AtomicBoolean(false)
    private val loadDeferred = CompletableDeferred<Unit>()

    // 内存缓存快照：初始为空，构造后在 IO 线程异步加载，避免主线程阻塞（P1）
    @Volatile
    private var cache: Preferences = preferencesOf()

    init {
        ioScope.launch {
            runCatching { loadCache() }.onFailure { it.printStackTrace() }
            loadDeferred.complete(Unit)
        }
    }

    // 确保缓存已从磁盘加载；仅在未加载时短暂阻塞（IO 线程），
    // 避免基于空缓存计算并写入导致旧数据丢失。
    // PERF-004：等待已在执行中的异步加载，而非重新发起 IO 操作
    private fun ensureLoaded() {
        if (!loaded.get()) {
            runBlocking(Dispatchers.IO) { loadDeferred.await() }
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

    fun getHistory(): List<String> {
        val raw = cache[KEY_HISTORY] ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        } catch (e: Exception) {
            // ARCH-008：历史数据损坏时降级为空列表，记录日志便于排查
            Log.w("SearchHistoryManager", "parse history failed", e)
            emptyList()
        }
    }

    fun addHistory(query: String) {
        if (query.isBlank()) return
        ensureLoaded()
        val current = getHistory().toMutableList()
        current.remove(query)
        current.add(0, query)
        val trimmed = if (current.size > MAX_HISTORY) current.subList(0, MAX_HISTORY) else current
        saveHistory(trimmed)
    }

    fun removeHistory(query: String) {
        ensureLoaded()
        val current = getHistory().toMutableList()
        current.remove(query)
        saveHistory(current)
    }

    fun clearAll() {
        editSync { it.remove(KEY_HISTORY) }
    }

    /**
     * 清除所有搜索历史（FEATURE-003 账号切换/登出时数据隔离）。
     */
    fun clearAllForAccountSwitch() {
        clearAll()
    }

    private fun saveHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        editSync { it[KEY_HISTORY] = arr.toString() }
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
        private val KEY_HISTORY = stringPreferencesKey("search_history")
        private const val MAX_HISTORY = 30
        private const val MIGRATION_DONE_KEY = "datastore_migration_done"
        private const val OLD_PREFS_NAME = "search_history_prefs"
        private const val OLD_KEY_HISTORY = "search_history"

        /**
         * 一次性迁移：把旧 SharedPreferences 数据导入 DataStore
         */
        private suspend fun migrateFromSharedPreferencesIfNeeded(context: Context) {
            val dataStore = context.searchHistoryDataStore
            val current = dataStore.data.first()
            if (current[booleanPreferencesKey(MIGRATION_DONE_KEY)] == true) return

            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            val oldHistory = oldPrefs.getString(OLD_KEY_HISTORY, null)
            dataStore.edit { prefs ->
                if (!oldHistory.isNullOrEmpty()) {
                    prefs[KEY_HISTORY] = oldHistory
                }
                prefs[booleanPreferencesKey(MIGRATION_DONE_KEY)] = true
            }
            context.deleteSharedPreferences(OLD_PREFS_NAME)
        }
    }
}
