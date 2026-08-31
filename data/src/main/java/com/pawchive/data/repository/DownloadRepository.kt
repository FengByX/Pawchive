package com.pawchive.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.pawchive.core.store.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一下载仓库（P1；ARCH-003：已迁移至 Hilt 构造函数注入）。
 *
 * 解决问题：此前下载逻辑硬编码到系统 Pictures/Movies 下的 Pawchive 目录，
 * 用户在设置页选择的 SAF 目录完全不生效。
 *
 * 策略：
 * 1. 优先使用 SettingsManager 中已授权的 SAF 树 URI，通过 DocumentFile 写入；
 * 2. 未配置 SAF 目录时回退到 MediaStore 默认路径（行为与原实现一致）；
 * 3. 提供 MIME 类型与显示名称，由仓库统一决定写入位置。
 */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) {

    enum class DownloadType(val mediaCollection: Uri?, val defaultRelativeDir: String) {
        IMAGE(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/Pawchive"),
        VIDEO(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/Pawchive"),
        /** Non-media files must use Downloads, otherwise MediaStore rejects or hides them. */
        ATTACHMENT(null, Environment.DIRECTORY_DOWNLOADS + "/Pawchive");

        fun contentUriForVolume(): Uri {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == IMAGE ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == VIDEO ->
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // MediaStore.Downloads 仅存在于 API 29+，必须在此守卫内访问，
                // 否则枚举初始化会直接在低版本设备上 NoClassDefFoundError 闪退。
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == ATTACHMENT ->
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // API < 29 无 MediaStore.Downloads；Files 集合自 API 1 即存在。
                this == ATTACHMENT -> MediaStore.Files.getContentUri("external")
                else -> mediaCollection!!
            }
        }
    }

    data class DownloadTarget(
        val type: DownloadType,
        val displayName: String,
        val mimeType: String
    )

    /** The saved URI is retained for both SAF and MediaStore so completed files can be opened. */
    data class OpenedDownloadStream(
        val outputStream: OutputStream,
        val uri: Uri,
        val requiresFinalize: Boolean
    )

    /**
     * 打开一个输出流用于写入下载内容。
     * 返回 Pair<OutputStream, Uri?>：Uri 仅在 MediaStore 路径下非空（用于后续 IS_PENDING 更新）。
     * SAF 路径下 Uri 为 null（DocumentFile 自管理）。
     *
     * 失败时抛出 Exception，调用方负责提示用户。
     */
    fun openDownloadStream(target: DownloadTarget): OpenedDownloadStream {
        val treeUri = settingsManager.getDownloadTreeUri()

        // 1) 优先使用 SAF 树 URI
        if (treeUri != null) {
            try {
                val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw Exception("SAF directory unavailable")
                if (!tree.canWrite()) throw Exception("SAF directory not writable")
                val file = tree.createFile(target.mimeType, target.displayName)
                    ?: throw Exception("Failed to create file in SAF directory")
                val os = context.contentResolver.openOutputStream(file.uri)
                    ?: throw Exception("Failed to open output stream")
                return OpenedDownloadStream(os, file.uri, requiresFinalize = false)
            } catch (e: Exception) {
                // SAF 不可用时降级到 MediaStore（不中断下载流程）
                e.printStackTrace()
            }
        }

        // 2) 回退到 MediaStore
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.type.defaultRelativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = target.type.contentUriForVolume()
        val uri = resolver.insert(collection, contentValues) ?: throw Exception("Failed to create media entry")
        try {
            val os = resolver.openOutputStream(uri) ?: throw Exception("Failed to open output stream")
            return OpenedDownloadStream(os, uri, requiresFinalize = true)
        } catch (e: Exception) {
            // The row is still hidden (IS_PENDING=1); delete it to avoid orphaned files.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * 下载完成后调用，标记 MediaStore 条目为已完成（IS_PENDING=0）。
     * SAF 路径下 uri 为 null，此方法为空操作。
     */
    fun finalizeDownload(uri: Uri?) {
        if (uri == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val contentValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            // ARCH-008：IS_PENDING 标记失败会导致 MediaStore 条目不可见，记录日志
            android.util.Log.w("DownloadRepository", "finalizeDownload failed: $uri", e)
        }
    }
}
