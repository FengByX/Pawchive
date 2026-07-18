package com.pawchive.data.repository

import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object CreatorNameCache {
    private val cache = ConcurrentHashMap<String, String>()

    // 限制创作者名称预取的并发请求数，避免瞬间发起数十个请求打满连接池、
    // 触发 Cloudflare 限流，从而拖慢首屏之后的图片加载与整体响应。
    private const val MAX_CONCURRENT_REQUESTS = 4

    private fun key(service: String, userId: String) = "$service:$userId"

    fun getCachedName(service: String, userId: String): String? {
        return cache[key(service, userId)]
    }

    fun cacheCreatorName(service: String, userId: String, name: String) {
        cache[key(service, userId)] = name
    }

    /**
     * 预取当前列表中所有创作者的显示名称并写入缓存。
     *
     * - 通过 [key] 正确构造缓存键做去重与命中判断（此前用 "$it.first:$it.second"
     *   拼接得到的是形如 "(patreon, 123).first" 的错误键，导致缓存过滤永不命中，
     *   每次进入首页都会重复请求所有创作者名称）。
     * - 使用 [Semaphore] 限制并发，避免请求风暴。
     * - 可选传入 [onNameResolved]，在单个名称解析成功后立即回调，便于 UI 增量刷新，
     *   而不必等待全部请求完成。
     */
    suspend fun prefetchCreatorNames(
        posts: List<Post>,
        onNameResolved: (() -> Unit)? = null
    ) {
        val uniqueCreators = posts
            .asSequence()
            .map { Pair(it.service, it.user) }
            .distinct()
            .filter { !cache.containsKey(key(it.first, it.second)) }
            .toList()

        if (uniqueCreators.isEmpty()) return

        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

        withContext(Dispatchers.IO) {
            coroutineScope {
                uniqueCreators.forEach { (service, userId) ->
                    launch {
                        val cacheKey = key(service, userId)
                        if (cache.containsKey(cacheKey)) return@launch
                        semaphore.withPermit {
                            try {
                                val profile = api.getCreatorProfile(service, userId)
                                cache[cacheKey] = profile.name
                                onNameResolved?.invoke()
                            } catch (e: Exception) {
                                // 静默失败
                            }
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        cache.clear()
    }

    private val api = ApiClient.publicApi
}
