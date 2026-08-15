package com.pawchive.data.repository

import android.text.Html
import com.google.gson.Gson
import com.pawchive.core.db.OfflineArchiveDao
import com.pawchive.core.model.OfflineArchiveEntity
import com.pawchive.core.model.Post
import com.pawchive.core.util.OfflineArchiveIndexer
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线归档仓库（ARCH-FEATURE-001：收藏内容离线索引与全文搜索）。
 *
 * 职责：
 * - 收藏时把帖子正文/标题/创作者/附件元数据预处理为纯文本并写入 Room 实体表；
 * - 同步写入 FTS4 影子表（CJK bigram 分词，见 [OfflineArchiveIndexer]）；
 * - 提供离线全文搜索与离线详情读取（postJson 完整序列化，无需网络）。
 *
 * 与 [BookmarkManager]（收藏主数据）通过 `service|creatorId|postId` 关联，
 * 由 BookmarkManager 在收藏/取消收藏/清空时调用本仓库保持同步。
 */
@Singleton
class OfflineArchiveRepository @Inject constructor(
    private val dao: OfflineArchiveDao,
    private val gson: Gson
) {

    /** 收藏/更新时索引帖子（幂等：主键冲突覆盖，FTS 先删后插）。事务保护防止实体与 FTS 不一致。 */
    suspend fun index(post: Post, favedAt: Long = System.currentTimeMillis()) {
        val id = archiveId(post.service, post.user, post.id)
        val plainTitle = post.title?.toPlainText()
        val plainContent = post.content?.toPlainText()
        val attachmentsText = buildList {
            post.file?.name?.let(::add)
            post.attachments?.mapNotNull { it.name }?.let(::addAll)
        }.distinct().joinToString(", ").ifBlank { null }

        val entry = OfflineArchiveEntity(
            id = id,
            service = post.service,
            creatorId = post.user,
            postId = post.id,
            title = plainTitle,
            contentText = plainContent,
            userName = post.userName,
            attachmentsText = attachmentsText,
            postJson = gson.toJson(post),
            favedAt = favedAt,
            updatedAt = System.currentTimeMillis()
        )
        dao.indexWithFts(
            entry = entry,
            title = OfflineArchiveIndexer.tokenize(plainTitle),
            content = OfflineArchiveIndexer.tokenize(plainContent),
            userName = OfflineArchiveIndexer.tokenize(post.userName),
            user = OfflineArchiveIndexer.tokenize(post.user),
            attachments = OfflineArchiveIndexer.tokenize(attachmentsText)
        )
    }

    /** 取消收藏时移除索引（实体行 + FTS 行）。事务保护防止实体与 FTS 不一致。 */
    suspend fun remove(service: String, creatorId: String, postId: String) {
        val id = archiveId(service, creatorId, postId)
        dao.deleteWithFts(id)
    }

    /** 账号切换/登出时清空全部离线索引。事务保护防止实体与 FTS 不一致。 */
    suspend fun clearAll() {
        dao.clearAllWithFts()
    }

    /** 离线归档总字节数（ARCH-FEATURE-004 缓存管理页展示）。 */
    suspend fun getTotalBytes(): Long = dao.getTotalBytes()

    /** 离线归档条数（ARCH-FEATURE-004 缓存管理页展示）。 */
    suspend fun getCount(): Int = dao.count()

    /** 观察全部离线归档（收藏时间倒序）。 */
    fun observeAll(): Flow<List<OfflineArchiveEntity>> = dao.observeAll()

    /** 读取单个离线归档（离线详情渲染）。 */
    suspend fun getEntry(service: String, creatorId: String, postId: String): OfflineArchiveEntity? =
        dao.getById(archiveId(service, creatorId, postId))

    /** 按归档 id 读取（搜索结果 id 已含分隔符）。 */
    suspend fun getEntryById(id: String): OfflineArchiveEntity? = dao.getById(id)

    /**
     * 离线全文搜索（ARCH-FEATURE-001 遗留项：相关性排序权重）。
     *
     * 用 FTS4 列过滤查询串（逐 token 加列前缀，无括号）按列拆查，再在应用层加权合并去重：
     * 标题命中 > 创作者名/id 命中 > 正文/附件命中；同一优先级内保持各列 FTS 命中顺序。
     * 不用 FTS4 bm25() 排序（Robolectric sqlite4java 未编译启用该函数）。
     */
    suspend fun search(query: String): List<OfflineArchiveEntity> {
        if (OfflineArchiveIndexer.toQuery(query) == "\"\"") return emptyList()
        // LinkedHashSet：按权重顺序收集并去重（已命中高权重的记录不会被低权重覆盖顺序）
        val orderedIds = LinkedHashSet<String>()
        dao.searchEntryIds(OfflineArchiveIndexer.toColumnQuery(query, "title"))
            .forEach { orderedIds.add(it) }
        dao.searchEntryIds(OfflineArchiveIndexer.toMultiColumnQuery(query, "userName", "user"))
            .forEach { orderedIds.add(it) }
        dao.searchEntryIds(OfflineArchiveIndexer.toMultiColumnQuery(query, "content", "attachments"))
            .forEach { orderedIds.add(it) }
        if (orderedIds.isEmpty()) return emptyList()
        val entries = dao.getByIds(orderedIds.toList())
        val order = orderedIds.withIndex().associate { it.value to it.index }
        return entries.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    private fun archiveId(service: String, creatorId: String, postId: String): String =
        "$service|$creatorId|$postId"

    private fun String.toPlainText(): String =
        Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
