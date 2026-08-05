package com.pawchive.core.store

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局内存缓存池（LRU + 过期时间）。
 * 应用运行期间所有页面共享，应用退出后自然释放。
 * 默认缓存条目过期时间：30 分钟。
 *
 * ARCH-003：已迁移至 Hilt 构造函数注入，移除 getInstance() 单例入口。
 */
@Singleton
class AppMemoryCache @Inject constructor() {

    private data class CacheEntry(
        val data: Any,
        val timestamp: Long
    )

    // 按条目数限制（约 200 条），兼顾内存占用与命中率
    private val cache: LruCache<String, CacheEntry> = LruCache(200)

    /**
     * 读取缓存。若不存在或已过期返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val entry = cache.get(key) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > DEFAULT_TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.data as? T
    }

    /**
     * 写入缓存。
     */
    fun put(key: String, data: Any) {
        cache.put(key, CacheEntry(data, System.currentTimeMillis()))
    }

    /**
     * 清除全部缓存。
     */
    fun clear() {
        cache.evictAll()
    }

    companion object {
        // 默认过期时间：30 分钟
        const val DEFAULT_TTL_MS = 30L * 60L * 1000L
    }
}
