package com.pawchive.core.di

import android.content.Context
import androidx.room.Room
import com.pawchive.core.db.ContentUpdateDao
import com.pawchive.core.db.CreatorSubscriptionDao
import com.pawchive.core.db.DownloadHistoryDao
import com.pawchive.core.db.DownloadRuleDao
import com.pawchive.core.db.OfflineArchiveDao
import com.pawchive.core.db.PawchiveDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 数据库提供模块（ARCH-004；ARCH-005：显式 Migration 替代 destructive 迁移；
 * ARCH-FEATURE-001：离线归档 DAO）。
 *
 * schema 变更必须提供显式 [androidx.room.migration.Migration]（见 PawchiveDatabase），
 * 禁止回退到 fallbackToDestructiveMigration，防止下载历史/离线归档数据丢失。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PawchiveDatabase {
        return Room.databaseBuilder(context, PawchiveDatabase::class.java, PawchiveDatabase.NAME)
            .addMigrations(
                PawchiveDatabase.MIGRATION_1_2,
                PawchiveDatabase.MIGRATION_2_3,
                PawchiveDatabase.MIGRATION_3_4,
                PawchiveDatabase.MIGRATION_4_5
            )
            .build()
    }

    @Provides
    fun provideDownloadHistoryDao(db: PawchiveDatabase): DownloadHistoryDao {
        return db.downloadHistoryDao()
    }

    @Provides
    fun provideOfflineArchiveDao(db: PawchiveDatabase): OfflineArchiveDao {
        return db.offlineArchiveDao()
    }

    @Provides
    fun provideDownloadRuleDao(db: PawchiveDatabase): DownloadRuleDao {
        return db.downloadRuleDao()
    }

    @Provides
    fun provideCreatorSubscriptionDao(db: PawchiveDatabase): CreatorSubscriptionDao {
        return db.creatorSubscriptionDao()
    }

    @Provides
    fun provideContentUpdateDao(db: PawchiveDatabase): ContentUpdateDao {
        return db.contentUpdateDao()
    }
}
