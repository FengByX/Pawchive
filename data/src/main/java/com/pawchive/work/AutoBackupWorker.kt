package com.pawchive.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pawchive.core.store.SettingsManager
import com.pawchive.data.repository.BackupManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 定时自动备份 Worker（FEATURE：设置项扩展批次三·定时自动备份）。
 *
 * 每天执行一次：调用 [BackupManager.export] 导出备份 JSON，
 * 经 SAF 写入用户在设置页选择的目录（tree URI）。
 * - 未选择备份目录：静默跳过（返回 success，避免无意义重试）；
 * - 写入失败：返回 retry，由 WorkManager 按退避策略重试。
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settingsManager: SettingsManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = settingsManager.getAutoBackupTreeUri()
        if (treeUri == null) {
            Log.i(TAG, "auto backup skipped: no backup folder selected")
            return Result.success()
        }
        return try {
            val json = backupManager.export()
            val fileName = "pawchive_auto_backup_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
            val documentUri = createDocument(treeUri, fileName)
                ?: return Result.retry()
            appContext.contentResolver.openOutputStream(documentUri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return Result.retry()
            settingsManager.setLastAutoBackupTime(System.currentTimeMillis())
            Log.i(TAG, "auto backup written: $fileName")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto backup failed", e)
            Result.retry()
        }
    }

    /** 在备份目录（tree URI 根）下创建新文档，返回其 Uri；失败返回 null。 */
    private fun createDocument(treeUri: Uri, fileName: String): Uri? {
        return try {
            DocumentsContract.createDocument(
                appContext.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                ),
                "application/json",
                fileName
            )
        } catch (e: Exception) {
            Log.w(TAG, "createDocument failed: $fileName", e)
            null
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"

        const val WORK_NAME = "auto_backup_daily"

        /**
         * 按开关调度/取消每日备份任务（App 启动与设置页开关切换时调用）。
         * UPDATE 策略保证全局唯一任务且保留既有排队节奏。
         */
        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
