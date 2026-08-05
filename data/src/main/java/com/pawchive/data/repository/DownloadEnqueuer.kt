package com.pawchive.data.repository

/**
 * 下载入队抽象（ARCH-FEATURE-002）。
 *
 * 由 [DownloadCenter] 实现；[DownloadRuleEngine] 依赖此接口而非具体类，
 * 便于在单测中注入虚实现验证批量入队逻辑。
 */
interface DownloadEnqueuer {

    /** 入队图片下载任务，返回下载记录 id。 */
    suspend fun enqueueImageDownload(url: String, fileName: String, mimeType: String = "image/jpeg"): String

    /** 入队视频下载任务，返回下载记录 id。 */
    suspend fun enqueueVideoDownload(url: String, fileName: String, mimeType: String = "video/mp4"): String

    /** 入队附件下载任务，返回下载记录 id。 */
    suspend fun enqueueAttachmentDownload(url: String, fileName: String, mimeType: String): String
}
