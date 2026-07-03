package com.pawchive.data.repository

import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

object CreatorNameCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun getCachedName(service: String, userId: String): String? {
        return cache["$service:$userId"]
    }

    fun cacheCreatorName(service: String, userId: String, name: String) {
        cache["$service:$userId"] = name
    }

    suspend fun prefetchCreatorNames(posts: List<Post>) {
        val uniqueCreators = posts
            .asSequence()
            .map { Pair(it.service, it.user) }
            .distinct()
            .filter { !cache.containsKey("$it.first:$it.second") }
            .toList()

        if (uniqueCreators.isEmpty()) return

        withContext(Dispatchers.IO) {
            uniqueCreators.map { (service, userId) ->
                async {
                    val cacheKey = "$service:$userId"
                    if (cache.containsKey(cacheKey)) return@async
                    try {
                        val profile = api.getCreatorProfile(service, userId)
                        cache[cacheKey] = profile.name
                    } catch (e: Exception) {
                        // 静默失败
                    }
                }
            }.forEach { it.await() }
        }
    }

    fun clear() {
        cache.clear()
    }

    private val api = ApiClient.publicApi
}
