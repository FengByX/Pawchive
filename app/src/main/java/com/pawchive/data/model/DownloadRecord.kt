package com.pawchive.data.model

import com.google.gson.annotations.SerializedName

/**
 * 下载记录实体（FEATURE-001 下载中心）。
 *
 * @param id 唯一标识，使用 url 作为 id（同一 URL 不重复下载）
 * @param url 下载源 URL
 * @param fileName 保存的文件名
 * @param mimeType MIME 类型（image/jpeg、video/mp4 等）
 * @param type 下载类型（IMAGE / VIDEO / ATTACHMENT）
 * @param status 下载状态
 * @param progress 下载进度（0-100）
 * @param fileSize 文件大小（字节），未知为 0
 * @param createdAt 创建时间戳
 * @param completedAt 完成时间戳，未完成为 0
 * @param filePath 保存路径（SAF 路径下为 DocumentFile uri，MediaStore 路径下为 media uri）
 * @param errorMessage 失败时的错误信息
 */
data class DownloadRecord(
    @SerializedName("id") val id: String,
    @SerializedName("url") val url: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("type") val type: DownloadType,
    @SerializedName("status") val status: DownloadStatus,
    @SerializedName("progress") val progress: Int = 0,
    @SerializedName("fileSize") val fileSize: Long = 0L,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("completedAt") val completedAt: Long = 0L,
    @SerializedName("filePath") val filePath: String? = null,
    @SerializedName("errorMessage") val errorMessage: String? = null
)

enum class DownloadType {
    IMAGE,
    VIDEO,
    ATTACHMENT
}

enum class DownloadStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
