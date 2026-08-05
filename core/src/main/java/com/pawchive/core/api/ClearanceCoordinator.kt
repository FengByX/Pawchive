package com.pawchive.core.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cloudflare 过盾协调器（ARCH-009 / BACKEND-001）。
 *
 * 统一负责"请求发出前确保 cf_clearance 已就绪"与"403 后的非阻塞预热"：
 * - [preheat]：非阻塞触发过盾，供 403 兜底拦截器与应用启动预热调用，
 *   不占用 OkHttp 网络线程（单飞由 CloudflareManager 保证，并发调用复用同一任务）；
 * - [ensureClearance]：挂起等待过盾结果，供 Repository / ViewModel 调用层在请求前使用。
 */
object ClearanceCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 非阻塞触发过盾（已有有效凭据时直接返回）。
     * 不阻塞调用线程，结果由后续请求自然受益。
     */
    fun preheat() {
        scope.launch {
            runCatching { CloudflareManager.ensureClearance() }
        }
    }

    /**
     * 挂起确保已过盾（CloudflareManager 内部带超时与单飞）。
     */
    suspend fun ensureClearance(forceRefresh: Boolean = false): Boolean {
        return CloudflareManager.ensureClearance(forceRefresh)
    }
}
