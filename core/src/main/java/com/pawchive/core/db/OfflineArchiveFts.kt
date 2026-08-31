package com.pawchive.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * 离线归档全文索引（ARCH-FEATURE-001，FTS4 影子表）。
 *
 * - 独立 FTS 表（不使用 contentEntity），[entryId] 冗余关联 [OfflineArchiveEntity]；
 * - 默认 simple tokenizer + 应用层 CJK bigram 预处理（见 OfflineArchiveIndexer），
 *   避免依赖平台 ICU/trigram，兼容所有 Android 版本的中英日文分词；
 * - rowid 由 SQLite 自增，写入/删除时与实体表显式同步（见 OfflineArchiveDao）。
 */
@Fts4
@Entity(tableName = "offline_archives_fts")
data class OfflineArchiveFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long = 0L,
    val entryId: String,
    val title: String?,
    val content: String?,
    val userName: String?,
    val user: String?,
    val attachments: String?
)
