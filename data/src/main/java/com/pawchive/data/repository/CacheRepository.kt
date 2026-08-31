package com.pawchive.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.pawchive.core.api.ApiMemoryCache
import com.pawchive.core.store.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存清理统一入口（ARCH-010：CacheRepository 职责；ARCH-FEATURE-004：分类统计与清理）。
 *
 * 手动清理（设置页）与自动清理（CacheCleanWorker）都通过本类执行，
 * 消除原先"AppCacheCleaner + 设置页手动删除 + Worker"三套并存导致的
 * 清理范围不一致。统一口径：
 * - [getCacheSize]：cacheDir + externalCacheDir（含 Coil image_cache，
 *   其内容由 Coil diskCache.clear() 清理，因此显示容量与实际可清理范围一致）；
 * - [clearCache]：Coil 内存+磁盘缓存、cacheDir（保留 image_cache 目录本身）、
 *   externalCacheDir、API 内存缓存（ApiMemoryCache），四类一次清完；
 * - 清理时间戳与自动清理阈值统一委托 [SettingsManager]。
 *
 * ARCH-FEATURE-004 分类口径（缓存管理页）：
 * - 图片缓存：Coil image_cache 目录（重新浏览会再下载，可安全清理）
 * - 其他缓存：cacheDir 其余部分 + externalCacheDir + API 内存缓存
 * - 下载文件：MediaStore Pictures/Pawchive 与 Movies/Pawchive + SAF 下载目录
 *   （用户下载产物，删除需二次确认）
 * - 离线归档大小由 OfflineArchiveRepository 提供（Room 数据）
 */
@Singleton
class CacheRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) {

    private val imageDiskCacheDirName = "image_cache"

    private companion object {
        const val TAG = "CacheRepository"

        /** MediaStore 下载目录路径特征（DownloadRepository 写入 Pictures/Pawchive、Movies/Pawchive）。 */
        const val DOWNLOAD_PATH_PATTERN = "%Pawchive%"
    }

    /**
     * 当前可清理缓存总大小（cacheDir + externalCacheDir）。
     */
    fun getCacheSize(): Long = SettingsManager.getCacheSize(context)

    /**
     * 执行一次完整清理（手动 / 自动共用入口）。
     * 各分项尽力而为，失败仅记录日志，不影响其余清理项。
     */
    fun clearCache() {
        clearImageCache()
        clearOtherCache()
    }

    // ========== 分类统计与清理（ARCH-FEATURE-004） ==========

    /** 图片缓存大小（Coil image_cache 目录）。 */
    fun getImageCacheSize(): Long = dirSize(File(context.cacheDir, imageDiskCacheDirName))

    /** 其他缓存大小（cacheDir 不含 image_cache + externalCacheDir）。 */
    fun getOtherCacheSize(): Long =
        (getCacheSize() - getImageCacheSize()).coerceAtLeast(0L)

    /** 清理图片缓存（Coil 内存 + 磁盘缓存；image_cache 目录本身保留）。 */
    fun clearImageCache() {
        try {
            val loader = coil.Coil.imageLoader(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "image cache clear failed", e)
        }
    }

    /** 清理其他缓存（cacheDir 非 image_cache 内容 + externalCacheDir + API 内存缓存）。 */
    fun clearOtherCache() {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name != imageDiskCacheDirName) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cacheDir cleanup failed", e)
        }
        try {
            context.externalCacheDir?.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "externalCacheDir cleanup failed", e)
        }
        ApiMemoryCache.clear()
    }

    /** 已下载文件总大小（MediaStore Pictures/Movies Pawchive + SAF 下载目录）。尽力而为，失败返回 0。 */
    fun getDownloadFilesSize(): Long {
        var total = 0L
        downloadCollections().forEach { collection ->
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(DOWNLOAD_PATH_PATTERN),
                    null
                )?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) total += cursor.getLong(sizeIndex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "query download size failed", e)
            }
        }
        settingsManager.getDownloadTreeUri()?.let { treeUri ->
            try {
                DocumentFile.fromTreeUri(context, treeUri)?.let { tree ->
                    total += documentDirSize(tree)
                }
            } catch (e: Exception) {
                Log.w(TAG, "query SAF download size failed", e)
            }
        }
        return total
    }

    /**
     * 删除已下载的图片 / 视频文件（MediaStore + SAF 下载目录）。
     * @return 删除的文件数；破坏性操作，调用方需二次确认。
     */
    fun deleteDownloadFiles(): Int {
        var deleted = 0
        downloadCollections().forEach { collection ->
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(DOWNLOAD_PATH_PATTERN),
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(collection, id)
                        if (context.contentResolver.delete(uri, null, null) > 0) deleted++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "delete MediaStore downloads failed", e)
            }
        }
        settingsManager.getDownloadTreeUri()?.let { treeUri ->
            try {
                DocumentFile.fromTreeUri(context, treeUri)?.let { tree ->
                    tree.listFiles().forEach { file -> if (file.delete()) deleted++ }
                }
            } catch (e: Exception) {
                Log.w(TAG, "delete SAF downloads failed", e)
            }
        }
        return deleted
    }

    // ---------- 内部工具 ----------

    private fun downloadCollections(): List<android.net.Uri> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { size += dirSize(it) }
        return size
    }

    private fun documentDirSize(dir: DocumentFile): Long {
        var size = 0L
        dir.listFiles().forEach { file ->
            size += if (file.isDirectory) documentDirSize(file) else file.length()
        }
        return size
    }

    /**
     * 记录本次清理时间戳（供设置页展示"上次清理时间"）。
     */
    fun recordClean() {
        settingsManager.setLastCacheCleanTime(System.currentTimeMillis())
    }

    /**
     * 上次清理时间（0 表示从未清理）。
     */
    fun getLastCleanTime(): Long = settingsManager.getLastCacheCleanTime()

    /**
     * 是否开启自动清理开关。
     */
    fun isAutoCleanEnabled(): Boolean = settingsManager.isAutoCleanCacheEnabled()

    /**
     * 是否应触发自动清理（容量超过阈值或从未清理过）。
     */
    fun shouldAutoClean(): Boolean = settingsManager.shouldAutoCleanByThreshold(context)
}
