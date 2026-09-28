package com.pawchive.data.repository

import com.pawchive.core.api.ApiClient
import com.pawchive.core.error.AppError
import com.pawchive.core.model.SimilarCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相似作者仓库（FEAT-SIMILAR-CREATORS）。
 *
 * Pawchive 服务端未在 /api/v1 开放相似作者端点，但网页版提供
 * `GET https://pawchive.pw/{service}/user/{creatorId}/recommended`
 * （Similar Artists 页，服务端渲染，返回与该作者最相关的至多 50 位创作者）。
 * 这里抓取该页并解析 user-card 锚点；共享客户端自带 Cloudflare 过盾与 403 重试。
 */
@Singleton
class SimilarCreatorsRepository @Inject constructor() {

    private companion object {
        const val WEB_BASE_URL = "https://pawchive.pw"

        /**
         * user-card 锚点：href 指向作者主页，class 以 user-card 开头。
         * 作者名在同锚点内的 <div class="user-card__name">…</div>。
         */
        val CARD_ANCHOR = Regex(
            pattern = """href="/(\w+)/user/([^"]+)"\s+class="user-card""",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val CARD_NAME = Regex("""user-card__name">([^<]*)<""")
    }

    /**
     * 拉取指定作者的相似作者列表（按站点推荐顺序）。
     * @throws AppError.Network/Server 网络失败或响应非 2xx 时由调用方降级处理
     */
    suspend fun fetchSimilarCreators(service: String, creatorId: String): List<SimilarCreator> {
        return withContext(Dispatchers.IO) {
            val url = "$WEB_BASE_URL/$service/user/$creatorId/recommended"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/html")
                .build()
            val response = try {
                ApiClient.sharedOkHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                throw AppError.from(e)
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw AppError.Server(resp.code, "recommended HTTP ${resp.code}")
                }
                val html = resp.body?.string().orEmpty()
                parseRecommended(html)
            }
        }
    }

    /** 解析 Similar Artists 页面中的创作者卡片；页面异常时返回空列表。 */
    internal fun parseRecommended(html: String): List<SimilarCreator> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<SimilarCreator>()
        val matches = CARD_ANCHOR.findAll(html).toList()
        for ((index, match) in matches.withIndex()) {
            val service = match.groupValues[1]
            val id = match.groupValues[2]
            // 名称在同一锚点块内（到下一个 user-card 或文档结尾）
            val blockEnd = matches.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(match.range.first, minOf(blockEnd, match.range.first + 4000))
            val name = CARD_NAME.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (results.none { it.service == service && it.id == id }) {
                results.add(SimilarCreator(service = service, id = id, name = name.ifEmpty { id }))
            }
        }
        return results
    }
}
