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
import org.json.JSONArray

// 顶层 DataStore 单例
private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history_prefs")

class SearchHistoryManager(context: Context) {

    private val dataStore = context.searchHistoryDataStore

    // 内存缓存快照
    @Volatile
    private var cache: Preferences = runBlocking {
        migrateFromSharedPreferencesIfNeeded(context)
        dataStore.data.first()
    }

    fun getHistory(): List<String> {
        val raw = cache[KEY_HISTORY] ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(query: String) {
        if (query.isBlank()) return
        val current = getHistory().toMutableList()
        current.remove(query)
        current.add(0, query)
        val trimmed = if (current.size > MAX_HISTORY) current.subList(0, MAX_HISTORY) else current
        saveHistory(trimmed)
    }

    fun removeHistory(query: String) {
        val current = getHistory().toMutableList()
        current.remove(query)
        saveHistory(current)
    }

    fun clearAll() {
        editSync { it.remove(KEY_HISTORY) }
    }

    private fun saveHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        editSync { it[KEY_HISTORY] = arr.toString() }
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
