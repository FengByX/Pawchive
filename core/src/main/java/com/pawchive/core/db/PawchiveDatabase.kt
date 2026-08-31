package com.pawchive.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pawchive.core.model.ContentUpdateEntity
import com.pawchive.core.model.CreatorSubscriptionEntity
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.OfflineArchiveEntity

/**
 * Pawchive 本地数据库（ARCH-004：下载历史存储迁移至 Room；ARCH-005：显式 Migration；
 * ARCH-FEATURE-001：离线归档与全文搜索索引；ARCH-FEATURE-002：下载规则表；
 * ARCH-FEATURE-003：创作者订阅与内容更新通知表）。
 *
 * schema 变更必须提供显式 [androidx.room.migration.Migration]（见 companion），
 * 禁止回退到 fallbackToDestructiveMigration，防止本地数据丢失。
 */
@Database(
    entities = [
        DownloadRecord::class,
        OfflineArchiveEntity::class,
        OfflineArchiveFts::class,
        DownloadRuleEntity::class,
        CreatorSubscriptionEntity::class,
        ContentUpdateEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PawchiveDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao

    abstract fun offlineArchiveDao(): OfflineArchiveDao

    abstract fun downloadRuleDao(): DownloadRuleDao

    abstract fun creatorSubscriptionDao(): CreatorSubscriptionDao

    abstract fun contentUpdateDao(): ContentUpdateDao

    companion object {
        const val NAME = "pawchive.db"

        /**
         * v1 → v2（ARCH-005）：下载主键从 url.hashCode() 迁移为 UUID。
         * 新增 dedupKey 去重指纹列与索引；旧记录保留原 id（url），dedupKey 为 null。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_records ADD COLUMN dedupKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_records_dedupKey ON download_records(dedupKey)")
            }
        }

        /**
         * v2 → v3（ARCH-FEATURE-001）：新增离线归档表 + FTS4 全文索引表。
         * 建表 SQL 与 Room schema 保持一致（FTS 表默认 simple tokenizer，
         * CJK 分词由应用层 bigram 预处理，见 OfflineArchiveIndexer）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_archives (
                        id TEXT NOT NULL PRIMARY KEY,
                        service TEXT NOT NULL,
                        creatorId TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        title TEXT,
                        contentText TEXT,
                        userName TEXT,
                        attachmentsText TEXT,
                        postJson TEXT NOT NULL,
                        favedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS offline_archives_fts USING fts4(
                        entryId, title, content, userName, user, attachments
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4（ARCH-FEATURE-002）：新增下载规则表。
         * 建表 SQL 与 Room schema 保持一致（fileType 以字符串存枚举名）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        creatorId TEXT,
                        service TEXT,
                        fileType TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        /**
         * v4 → v5（ARCH-FEATURE-003）：新增创作者订阅表 + 内容更新通知表。
         * 建表 SQL 与 Room schema 保持一致（复合主键、唯一索引、read 标记）。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creator_subscriptions (
                        service TEXT NOT NULL,
                        creatorId TEXT NOT NULL,
                        name TEXT,
                        lastPostId TEXT,
                        subscribedAt INTEGER NOT NULL,
                        PRIMARY KEY(service, creatorId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_creator_subscriptions_service ON creator_subscriptions(service)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_updates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        service TEXT NOT NULL,
                        creatorId TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        postTitle TEXT,
                        discoveredAt INTEGER NOT NULL,
                        read INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_content_updates_service ON content_updates(service)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_content_updates_service_creatorId_postId " +
                        "ON content_updates(service, creatorId, postId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_content_updates_read ON content_updates(read)"
                )
            }
        }
    }
}
