package com.pawchive.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃处理器（FEATURE-006 崩溃埋点）。
 *
 * - 捕获未处理的异常，写入崩溃日志文件
 * - 日志存储在应用缓存目录的 crash_logs/ 下
 * - 可通过 getCrashLogs() 获取崩溃记录供用户反馈
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crash_logs"
    private const val MAX_LOG_FILES = 10

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 写入崩溃日志
            runCatching {
                writeCrashLog(context, thread, throwable)
            }
            // 调用原始 handler 让系统正常处理崩溃
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val crashDir = File(context.cacheDir, CRASH_DIR)
        if (!crashDir.exists()) crashDir.mkdirs()

        // 清理旧日志，保留最近 MAX_LOG_FILES 个
        crashDir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOG_FILES)?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val logFile = File(crashDir, "crash_$timestamp.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("=== Pawchive Crash Report ===")
        pw.println("Time: $timestamp")
        pw.println("Thread: ${thread.name}")
        pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("App Version: ${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "unknown" }}")
        pw.println()
        pw.println("Stack Trace:")
        throwable.printStackTrace(pw)
        pw.close()

        logFile.writeText(sw.toString())
    }

    /**
     * 获取所有崩溃日志文件列表。
     */
    fun getCrashLogs(context: Context): List<File> {
        val crashDir = File(context.cacheDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 清除所有崩溃日志。
     */
    fun clearCrashLogs(context: Context) {
        val crashDir = File(context.cacheDir, CRASH_DIR)
        crashDir.listFiles()?.forEach { it.delete() }
    }
}
