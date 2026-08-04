package com.pawchive.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.pawchive.data.model.DownloadRecord
import com.pawchive.data.model.DownloadStatus
import com.pawchive.data.model.DownloadType
import com.pawchive.work.DownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 下载中心统一入口（FEATURE-001）。
 *
 * 职责：
 * - 统一图片/视频/附件下载队列管理
 * - 通过 WorkManager 调度下载任务，支持后台持续、取消、重试
 * - 下载记录通过 DownloadHistoryManager 持久化
 * - 提供 WorkManager 任务状态 Flow 供 UI 订阅
 *
 * 使用方式：
 * ```
 * val center = DownloadCenter.getInstance(context)
 * center.enqueueImageDownload(url, fileName, mimeType)
 * center.observeWorkInfo(fileName).collect { workInfo -> ... }
 * center.cancel(fileName)
 * center.retry(fileName)
 * ```
 */
object DownloadCenter {

    const val WORK_TAG = "pawchive_download"
    private const val WORK_NAME_PREFIX = "download:"

    /**
     * 入队图片下载任务。
     * 同一 URL 的任务不重复入队（ExistingWorkPolicy.KEEP）。
     */
    suspend fun enqueueImageDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String = "image/jpeg"
    ): String {
        return enqueueDownload(context, url, fileName, mimeType, DownloadType.IMAGE)
    }

    /**
     * 入队视频下载任务。
     */
    suspend fun enqueueVideoDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String = "video/mp4"
    ): String {
        return enqueueDownload(context, url, fileName, mimeType, DownloadType.VIDEO)
    }

    /**
     * 入队附件下载任务。
     */
    suspend fun enqueueAttachmentDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String
    ): String {
        return enqueueDownload(context, url, fileName, mimeType, DownloadType.ATTACHMENT)
    }

    private suspend fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        type: DownloadType
    ): String {
        val workName = "$WORK_NAME_PREFIX${url.hashCode()}"
        val historyManager = DownloadHistoryManager.getInstance(context)

        // 创建下载记录
        val record = DownloadRecord(
            id = url,
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            type = type,
            status = DownloadStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        historyManager.upsert(record)

        // 入队 WorkManager 任务
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, url)
            .putString(DownloadWorker.KEY_FILE_NAME, fileName)
            .putString(DownloadWorker.KEY_MIME_TYPE, mimeType)
            .putString(DownloadWorker.KEY_DOWNLOAD_TYPE, type.name)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .addTag(workName)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            request
        )

        return workName
    }

    /**
     * 取消指定下载任务。
     */
    fun cancel(context: Context, url: String) {
        val workName = "$WORK_NAME_PREFIX${url.hashCode()}"
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    /**
     * 重试下载任务（取消旧任务后重新入队）。
     */
    suspend fun retry(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        type: DownloadType
    ): String {
        cancel(context, url)
        return enqueueDownload(context, url, fileName, mimeType, type)
    }

    /**
     * 观察指定下载任务的工作状态。
     */
    fun observeWorkInfo(context: Context, url: String): Flow<List<WorkInfo>> {
        val workName = "$WORK_NAME_PREFIX${url.hashCode()}"
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName)
    }

    /**
     * 观察所有下载任务的工作状态。
     */
    fun observeAllWorkInfo(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(WORK_TAG)
    }

    /**
     * 获取所有下载历史记录的 StateFlow。
     */
    fun observeHistory(context: Context) = DownloadHistoryManager.getInstance(context).records

    /**
     * 移除下载历史记录（不影响已保存的文件）。
     */
    suspend fun removeHistory(context: Context, url: String) {
        DownloadHistoryManager.getInstance(context).remove(url)
    }

    /**
     * 清空所有下载历史（不影响已保存的文件）。
     */
    suspend fun clearAllHistory(context: Context) {
        DownloadHistoryManager.getInstance(context).clearAll()
    }
}
