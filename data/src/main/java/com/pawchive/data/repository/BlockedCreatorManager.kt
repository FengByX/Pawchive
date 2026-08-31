package com.pawchive.data.repository

import android.content.Context
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// 顶层 DataStore 单例
private val Context.blockedCreatorsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blocked_creators")

// 共享 IO 作用域：替代 GlobalScope，明确运行在 IO 线程并带 SupervisorJob 隔离异常（P1）
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 屏蔽创作者管理器（ARCH-003：已迁移至 Hilt 构造函数注入）。
 * 使用 DataStore 存储屏蔽列表，内存缓存快照实现同步高频读取。
 * 屏蔽键格式："blocked_service|creatorId"（与 BookmarkManager 一致）。
 */
@Singleton
class BlockedCreatorManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val dataStore = context.blockedCreatorsDataStore
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

    // 确保缓存已从磁盘加载；挂起函数，不阻塞调用线程（P1）。
    // 调用方应在协程中调用（如 blockCreator/unblockCreator），读取操作直接用缓存快照。
    private suspend fun ensureLoaded() {
        if (!loaded.get()) {
            loadDeferred.await()
        }
    }

    private suspend fun loadCache() {
        writeMutex.withLock {
            if (!loaded.get()) {
                cache = dataStore.data.first()
                loaded.set(true)
            }
        }
    }

    /**
     * 暴露屏蔽列表的 Flow，UI 层用 repeatOnLifecycle 收集以自动刷新（P1）。
     * 解决异步加载竞态：首次进入页面不再出现空数据，加载完成后 UI 自动更新。
     */
    val blockedCreatorsFlow: Flow<List<Pair<String, String>>> = dataStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) && it.value == true }
            .mapNotNull { entry ->
                val parts = entry.key.name.removePrefix(KEY_PREFIX).split("|")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
    }

    /**
     * 判断指定创作者是否已被屏蔽（同步读取内存缓存快照，不阻塞）。
     * 冷启动瞬间可能返回 false（缓存尚未加载），UI 通过 blockedCreatorsFlow 自动刷新。
     */
    fun isCreatorBlocked(service: String, creatorId: String): Boolean {
        return cache[getBlockKey(service, creatorId)] ?: false
    }

    /**
     * 屏蔽指定创作者（挂起函数，需在协程中调用）。
     */
    suspend fun blockCreator(service: String, creatorId: String) {
        ensureLoaded()
        editSync { it[getBlockKey(service, creatorId)] = true }
    }

    /**
     * 取消屏蔽指定创作者（挂起函数，需在协程中调用）。
     */
    suspend fun unblockCreator(service: String, creatorId: String) {
        ensureLoaded()
        editSync { it.remove(getBlockKey(service, creatorId)) }
    }

    /**
     * 获取所有被屏蔽的创作者列表（同步读取内存缓存快照，不阻塞）。
     * 冷启动瞬间可能为空，UI 通过 blockedCreatorsFlow 自动刷新。
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
     * 被屏蔽的创作者数量（同步读取内存缓存快照，不阻塞）。
     */
    fun getBlockedCount(): Int = getBlockedCreators().size

    // ---------- ARCH-FEATURE-005 备份导入 ----------

    /** 清空全部屏蔽记录。 */
    suspend fun clearAll() {
        val updated = dataStore.edit { it.clear() }
        cache = updated
    }

    /** 批量导入屏蔽名单（覆盖现有数据），单次 DataStore 写入。 */
    suspend fun importAll(creators: List<Pair<String, String>>) {
        val updated = dataStore.edit { prefs ->
            prefs.clear()
            creators.forEach { (service, creatorId) ->
                prefs[getBlockKey(service, creatorId)] = true
            }
        }
        cache = updated
    }

    private fun getBlockKey(service: String, creatorId: String): Preferences.Key<Boolean> {
        return booleanPreferencesKey("$KEY_PREFIX$service|$creatorId")
    }

    /**
     * 同步更新内存缓存（保证 UI 立即可见）+ 串行、带异常处理地落盘（P1）。
     * 使用 writeMutex 保护“缓存更新 + 磁盘写入”原子性，防止并发调用导致不一致。
     * 在 Mutex 内以 runBlocking 同步等待 DataStore 写入完成（调用方已在协程/IO 线程）。
     */
    private suspend fun editSync(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeMutex.withLock {
            // 先更新内存缓存（保证后续同步读取立即可见）
            cache = cache.toMutablePreferences().apply { block(this) }
            // 同步落盘（由 Mutex 串行化并发写入）
            val updated = runCatching { dataStore.edit(block) }.getOrDefault(cache)
            // 以磁盘最新状态刷新内存快照，保持权威一致
            cache = updated
        }
    }

    companion object {
        private const val KEY_PREFIX = "blocked_"
    }
}
