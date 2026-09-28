package com.pawchive.data.repository

import com.pawchive.core.model.DownloadType

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

    /**
     * 入队"跳过下载"记录（FEAT-PREVIEW-ONLY-SKIP）。
     *
     * 文件带 preview_only 标记（站点仅收录预览、原图未导入文件服务器，直接下载必 404）
     * 时不发起下载，仅落一条 FAILED + [com.pawchive.core.model.DownloadRecord.ERROR_PREVIEW_ONLY]
     * 原因的记录；站点补齐后在下载中心重试即可。
     */
    suspend fun enqueuePreviewOnlySkipped(
        url: String,
        fileName: String,
        mimeType: String,
        type: DownloadType
    ): String
}
