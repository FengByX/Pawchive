package com.pawchive.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
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
        val requiresFinalize: Boolean,
        /** true 表示写入的是 SAF 文档（DocumentFile 自管理，无需 IS_PENDING 收尾）。 */
        val isSaf: Boolean
    )

    /**
     * 净化显示名。
     *
     * MediaStore 的 DISPLAY_NAME 与 DocumentsProvider 的 createDocument 都拒绝路径
     * 分隔符与控制字符。服务端返回的文件名可能形如 `sub/dir/图 片.jpg`，直接写入
     * MediaStore 会抛 IllegalArgumentException，表现为"保存失败"且没有任何文件产出。
     */
    fun sanitizeDisplayName(name: String): String {
        val cleaned = ILLEGAL_NAME_CHARS.replace(name.trim(), "_")
        return cleaned.ifEmpty { "download" }
    }

    /**
     * 打开一个输出流用于写入下载内容。
     *
     * 返回 [OpenedDownloadStream]：
     * - SAF 路径：uri 为文档 URI，requiresFinalize=false（DocumentFile 自管理）；
     * - MediaStore 路径：uri 为媒体条目 URI，requiresFinalize=true（需 [finalizeDownload] 收尾）。
     *
     * 失败时抛出 Exception，调用方负责提示用户。
     */
    fun openDownloadStream(target: DownloadTarget): OpenedDownloadStream {
        val displayName = sanitizeDisplayName(target.displayName)
        val treeUri = settingsManager.getDownloadTreeUri()

        // 1) 优先使用 SAF 树 URI
        if (treeUri != null) {
            try {
                val tree = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw Exception("SAF directory unavailable")
                // 不预先用 tree.canWrite() 判定：部分 DocumentsProvider 对树 URI 恒返回
                // false，会让用户已授权并持久化的目录被误判为不可写，导致设置页里
                // 选定的下载位置永远不生效、文件偷偷落到 MediaStore 默认目录。
                // 直接尝试创建，失败再由下面的 catch 统一降级。
                val file = tree.createFile(target.mimeType, displayName)
                    ?: throw Exception("Failed to create file in SAF directory")
                val os = try {
                    context.contentResolver.openOutputStream(file.uri)
                } catch (e: Exception) {
                    runCatching { file.delete() }
                    throw e
                }
                if (os == null) {
                    runCatching { file.delete() }
                    throw Exception("Failed to open output stream")
                }
                return OpenedDownloadStream(os, file.uri, requiresFinalize = false, isSaf = true)
            } catch (e: Exception) {
                // SAF 不可用时降级到 MediaStore（不中断下载流程）
                Log.w(TAG, "SAF write unavailable, falling back to MediaStore", e)
            }
        }

        // 2) 回退到 MediaStore
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
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
            return OpenedDownloadStream(os, uri, requiresFinalize = true, isSaf = false)
        } catch (e: Exception) {
            // The row is still hidden (IS_PENDING=1); delete it to avoid orphaned files.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * 放弃一次未完成的写入，删除半截文件。
     *
     * MediaStore 下条目处于 IS_PENDING=1（对系统不可见但占用存储），
     * SAF 下则是一个 0 字节空文档。此前下载失败/取消从不清理，反复重试会在
     * 用户目录里堆出一堆看不见的垃圾文件。
     */
    fun abandonDownload(uri: Uri?, isSaf: Boolean) {
        if (uri == null) return
        try {
            if (isSaf) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon download: $uri", e)
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
            Log.w(TAG, "finalizeDownload failed: $uri", e)
        }
    }

    private companion object {
        const val TAG = "DownloadRepository"
        /** MediaStore / DocumentsProvider 均不接受的文件名字符（路径分隔符、控制字符等）。 */
        val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    }
}
