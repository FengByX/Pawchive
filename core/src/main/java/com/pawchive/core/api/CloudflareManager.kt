package com.pawchive.core.api

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.http.SslError
import android.webkit.CookieManager
import com.pawchive.core.BuildConfig
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 负责通过 WebView 自动通过 pawchive.pw 的 Cloudflare 托管挑战（Managed Challenge）。
 *
 * 目标站点启用了 Cloudflare 防护，纯 OkHttp/Retrofit 请求（无 JS 引擎）会被拦截返回 403。
 * 此管理器用一个隐藏 WebView 加载首页，让其执行 Cloudflare 的 JavaScript 挑战，
 * 通过后从 CookieManager 中提取 cf_clearance 等 cookie，并缓存 WebView 的 User-Agent。
 *
 * 重要：cf_clearance 与请求的 User-Agent 强绑定，注入到 OkHttp 时必须使用同一个 UA。
 * cookie 有有效期，过期后需要重新过盾（clearance() 会返回 null 触发刷新）。
 */
object CloudflareManager {

    private const val BASE_URL = "https://pawchive.pw/"
    private const val HOST = "pawchive.pw"
    private const val CF_CLEARANCE = "cf_clearance"

    /**
     * 应用专属 User-Agent。
     *
     * 文件服务器（file.pawchive.pw / img.pawchive.pw）运维方明确要求：
     * - 使用可识别的 UA（最好带联系方式），不要伪装成浏览器；
     * - cf_clearance 与过盾时使用的 UA 强绑定，因此 WebView 过盾前必须把 UA 设为该值，
     *   后续 OkHttp 注入的也是同一个 UA，过盾与请求保持一致。
     */
    const val APP_USER_AGENT = "Pawchive-Android (https://github.com/FengByX/Pawchive)"

    // 过盾超时时间（毫秒）
    private const val CHALLENGE_TIMEOUT_MS = 30_000L
    // 轮询 cookie 的间隔（毫秒）
    private const val POLL_INTERVAL_MS = 500L

    // 持久化存储相关
    private const val PREFS_NAME = "cloudflare_clearance"
    private const val KEY_COOKIE = "cf_cookie"
    private const val KEY_USER_AGENT = "cf_user_agent"
    private const val KEY_SAVED_AT = "cf_saved_at"
    // cf_clearance 通常有效期约 30 分钟，这里保守设为 25 分钟（预留约 5 分钟余量），
    // 过期后主动重新过盾；前台恢复时 preheat 会提前刷新，避免页面加载等待
    private const val CLEARANCE_TTL_MS = 25 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    // 缓存凭据的保存时间戳（毫秒）
    @Volatile
    private var cachedSavedAt: Long = 0L

    // 缓存的完整 cookie 字符串（含 cf_clearance 等），用于注入 OkHttp
    @Volatile
    private var cachedCookie: String? = null

    // 与 cf_clearance 绑定的 WebView User-Agent
    @Volatile
    private var cachedUserAgent: String? = null

    // 单飞：进行中的过盾任务，并发调用复用同一结果，避免并发启动多个 WebView
    @Volatile
    private var inFlight: CompletableDeferred<Boolean>? = null
    private val flightLock = Any()
    private val cfScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // PERF-011：持久化凭据恢复任务（后台执行）。
    // ensureClearance() 会先 join 该任务，避免"磁盘上有有效凭据但内存尚未恢复"时
    // 被误判为无凭据而重复启动 WebView 过盾——这是冷启动首屏白等 5-15s 的根因。
    @Volatile
    private var restoreJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // PERF-002：移至后台协程，避免 EncryptedSharedPreferences 阻塞主线程冷启动
        restoreJob = cfScope.launch { loadPersisted() }
    }

    /**
     * 从加密存储中恢复上次过盾成功的凭据，避免冷启动重新过盾。
     * 若凭据已超过 TTL 则视为过期，不恢复。
     */
    private fun loadPersisted() {
        val ctx = appContext ?: return
        val t0 = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
        try {
            val sp = prefs ?: run {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefs = it }
            }
            val cookie = sp.getString(KEY_COOKIE, null)
            val ua = sp.getString(KEY_USER_AGENT, null)
            val savedAt = sp.getLong(KEY_SAVED_AT, 0L)
            if (!cookie.isNullOrEmpty() && !ua.isNullOrEmpty() &&
                System.currentTimeMillis() - savedAt < CLEARANCE_TTL_MS
            ) {
                cachedCookie = stripSessionTokens(cookie)
                cachedUserAgent = ua
                cachedSavedAt = savedAt
            }
        } catch (_: Exception) {
            // 加密存储不可用时降级为仅内存缓存，不影响功能
   }
        if (BuildConfig.DEBUG) {
            Log.d(
                "CloudflareManager",
                "loadPersisted cost=${System.currentTimeMillis() - t0}ms, hasClearance=${hasClearance()}"
            )
        }
    }

    /**
     * 将过盾凭据持久化到加密存储。
     */
    private fun persist(cookie: String, userAgent: String) {
        val clean = stripSessionTokens(cookie)
        val now = System.currentTimeMillis()
        cachedSavedAt = now
        try {
            prefs?.edit()
                ?.putString(KEY_COOKIE, clean)
                ?.putString(KEY_USER_AGENT, userAgent)
                ?.putLong(KEY_SAVED_AT, now)
                ?.apply()
        } catch (e: Exception) {
            // ARCH-008：凭据持久化失败会导致下次冷启动重新过盾，记录日志
            Log.w("CloudflareManager", "persist clearance failed", e)
        }
    }

    /**
     * 剔除 cookie 字符串中的 `session=` 段（登录失效循环的根因防护）。
     *
     * WebView 过盾加载的页面（Flask 首页等）会写入匿名 session cookie，
     * 若原样注入 OkHttp 请求，会与 SessionInterceptor 注入的真实登录 session
     * 形成重复 session cookie（服务端取到匿名会话 → 401 → 误判登录失效 → 登录循环）。
     * 注入请求的 cookie 一律不允许携带 session 段，真实会话只由 SessionInterceptor 提供。
     */
    fun stripSessionTokens(cookie: String): String {
        return cookie.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("session=", ignoreCase = true) }
            .joinToString("; ")
            .trim()
    }

    /**
     * 返回当前已缓存的 cookie 字符串；若尚未过盾成功则为 null。
     */
    fun currentCookie(): String? = cachedCookie

    /**
     * 返回当前应注入请求的 User-Agent。
     *
     * 过盾成功后使用与 cf_clearance 绑定的 UA（即 APP_USER_AGENT）；
     * 过盾前也返回 APP_USER_AGENT 作为兜底，确保文件/图片服务器始终收到可识别的 UA，
     * 而非 OkHttp 默认 UA 或浏览器 UA。
     */
    fun currentUserAgent(): String = cachedUserAgent ?: APP_USER_AGENT

    /**
     * 是否已有可用的 Cloudflare 通行凭据（且未过期）。
     */
    fun hasClearance(): Boolean {
        if (cachedCookie.isNullOrEmpty()) return false
        // 凭据超过 TTL 视为过期，需要重新过盾
        return System.currentTimeMillis() - cachedSavedAt < CLEARANCE_TTL_MS
    }

    /**
     * 确保已通过 Cloudflare 挑战。若已缓存则直接返回 true；
     * 否则启动 WebView 执行挑战，成功返回 true，超时/失败返回 false。
     * 该方法为挂起函数，需在协程中调用。
     */
    suspend fun ensureClearance(forceRefresh: Boolean = false): Boolean {
        // PERF-011：内存已有有效凭据时直接返回，跳过磁盘恢复等待
        // （EncryptedSharedPreferences 的 MasterKey 首次创建在某些设备上很慢，
        //  仅在内存无凭据时才 join 磁盘恢复任务，避免每次请求都付出该开销）
        if (!forceRefresh && hasClearance()) return true
        restoreJob?.join()
        if (!forceRefresh && hasClearance()) return true
        // 单飞：临界区内只做状态判断（不调用挂起函数），创建/复用结果在区外 await
        val (deferred, isNew) = synchronized(flightLock) {
            inFlight?.let { return@synchronized it to false } // 已有进行中的过盾，复用其结果
            val d = CompletableDeferred<Boolean>()
            inFlight = d
            d to true
        }
        // 仅当本次新建任务时才启动 WebView；并发调用复用同一 deferred，不再另起过盾
        if (isNew) {
            val startTime = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            cfScope.launch {
                try {
                    val ok = solveChallenge()
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "CloudflareManager",
                            "solveChallenge result=$ok, cost=${System.currentTimeMillis() - startTime}ms"
                        )
                    }
                    deferred.complete(ok)
                } catch (e: Throwable) {
                    if (!deferred.isCompleted) deferred.completeExceptionally(e)
                } finally {
                    synchronized(flightLock) { inFlight = null }
                }
            }
        } else if (BuildConfig.DEBUG) {
            Log.d("CloudflareManager", "ensureClearance reused in-flight solve (concurrent caller)")
        }
        if (BuildConfig.DEBUG && !hasClearance()) {
            Log.d("CloudflareManager", "ensureClearance: no valid memory clearance, awaiting WebView challenge")
        }
        return runCatching { deferred.await() }.getOrDefault(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveChallenge(): Boolean {
        val context = appContext ?: return false

        return suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post {
                // WebView 的实现方是可以被替换的（用 Magisk 模块把系统 WebView 换成
                // Cromite / Bromite 等第三方实现很常见）。这类实现可能缺少 native 库、
                // 未被当前 ROM 注册为合法 provider，或与其 SELinux 策略不匹配，构造阶段
                // 就会抛 MissingWebViewPackageException / UnsatisfiedLinkError /
                // RuntimeException。若让异常逃逸，它会从主线程的 Runnable 直接进入
                // UncaughtExceptionHandler 并终结进程（表现为启动后闪退），
                // 因此这里就地降级为"过盾失败"，绝不向上传播。
                val challengeWebView = createChallengeWebView(context)
                if (challengeWebView == null) {
                    if (cont.isActive) cont.resume(false)
                    return@post
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(challengeWebView, true)

                // 与 cf_clearance 绑定的 UA：过盾与后续 OkHttp 请求必须保持一致
                val userAgent = challengeWebView.settings.userAgentString ?: APP_USER_AGENT

                var finished = false

                fun finish(success: Boolean) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(null)
                    try {
                        challengeWebView.stopLoading()
                        challengeWebView.destroy()
                    } catch (e: Exception) {
                        Log.w("CloudflareManager", "webView destroy failed", e)
                    }
                    if (cont.isActive) cont.resume(success)
                }

                // 轮询检测 cf_clearance cookie 是否出现
                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (finished) return
                        // 取 cookie 本身也可能抛错（第三方 WebView 实现的网络层可能只
                        // 捕获 IOException，SecurityException 会漏出来），失败即视为未就绪
                        val cookie = runCatching { cookieManager.getCookie(BASE_URL) }.getOrNull()
                        if (!cookie.isNullOrEmpty() && cookie.contains(CF_CLEARANCE)) {
                            val clean = stripSessionTokens(cookie)
                            cachedCookie = clean
                            cachedUserAgent = userAgent
                            cookieManager.flush()
                            persist(clean, userAgent)
                            finish(true)
                        } else {
                            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }

                challengeWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 页面加载完成后开始轮询 cookie（挑战可能在页面加载后异步完成）
                        if (!finished) {
                            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                        }
                    }

                    /**
                     * 渲染进程崩溃时必须返回 true。
                     *
                     * Android 的默认语义：宿主未覆写该方法（或返回 false）时，渲染进程一旦
                     * 崩溃，系统会连带终止整个应用进程——用户看到的就是"闪退"，而且这条
                     * 路径不产生 Java 堆栈，崩溃日志里什么都留不下。
                     * 第三方 WebView 实现普遍强开严格站点隔离，崩溃概率明显高于系统实现，
                     * 这里改为自行回收 WebView 并让过盾降级。
                     */
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        Log.w(
                            "CloudflareManager",
                            "WebView render process gone (crashed=${detail?.didCrash()}), " +
                                "abort challenge to keep app alive"
                        )
                        finish(false)
                        return true
                    }

                    /**
                     * 主文档加载失败立即结束，不再空等 CHALLENGE_TIMEOUT_MS。
                     * 只对主文档生效：挑战过程中被拦掉的子资源（广告/追踪请求）失败
                     * 不应中断过盾。
                     */
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w(
                                "CloudflareManager",
                                "challenge page load failed: " +
                                    "${error?.errorCode} ${error?.description}"
                            )
                            finish(false)
                        }
                    }

                    /**
                     * 证书错误无法继续挑战，显式取消并降级（绝不 proceed）。
                     */
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        Log.w(
                            "CloudflareManager",
                            "challenge page SSL error: ${error?.primaryError}"
                        )
                        handler?.cancel()
                        finish(false)
                    }
                }

                // 超时兜底
                mainHandler.postDelayed({
                    // 超时前再尝试取一次 cookie
                    val cookie = runCatching { cookieManager.getCookie(BASE_URL) }.getOrNull()
                    if (!cookie.isNullOrEmpty() && cookie.contains(CF_CLEARANCE)) {
                        val clean = stripSessionTokens(cookie)
                        cachedCookie = clean
                        cachedUserAgent = userAgent
                        cookieManager.flush()
                        persist(clean, userAgent)
                        finish(true)
                    } else {
                        finish(false)
                    }
                }, CHALLENGE_TIMEOUT_MS)

                try {
                    challengeWebView.loadUrl(BASE_URL)
                } catch (t: Throwable) {
                    // loadUrl 会触发渲染进程创建，第三方 WebView 实现可能在此同步抛错
                    Log.w("CloudflareManager", "loadUrl failed, abort challenge", t)
                    finish(false)
                }

                cont.invokeOnCancellation {
                    mainHandler.post { finish(false) }
                }
            }
        }
    }

    /**
     * 创建并配置用于过盾的 WebView；当前设备无法使用 WebView 时返回 null。
     *
     * 刻意捕获 Throwable 而非 Exception：加载不到 native 库时抛出的
     * UnsatisfiedLinkError 属于 Error，同样会直接终结进程。
     */
    private fun createChallengeWebView(context: Context): WebView? {
        return try {
            WebView(context).apply {
                val webSettings: WebSettings = settings
                webSettings.javaScriptEnabled = true
                webSettings.domStorageEnabled = true
                webSettings.databaseEnabled = true
                // 使用应用专属 UA 过盾（而非 WebView 默认的浏览器 UA）。
                // cf_clearance 与过盾 UA 强绑定，后续 OkHttp 注入同一个 UA，过盾与请求一致。
                webSettings.userAgentString = APP_USER_AGENT
            }
        } catch (t: Throwable) {
            Log.w(
                "CloudflareManager",
                "WebView unavailable, challenge disabled (current WebView " +
                    "implementation may be unsupported)",
                t
            )
            null
        }
    }

    /**
     * 清除缓存的凭据，下次请求会重新过盾。
     */
    fun clear() {
        cachedCookie = null
        cachedUserAgent = null
        cachedSavedAt = 0L
        try {
            prefs?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            Log.w("CloudflareManager", "clear clearance prefs failed", e)
        }
    }

    /**
     * 取消内部协程作用域，释放资源（ARCH-BUG-MINOR-16）。
     * 应在 Application 销毁/低内存时调用。
     */
    fun shutdown() {
        cfScope.coroutineContext.cancel()
    }

    /**
     * 判断异常是否为 Cloudflare/服务端返回的 403（被拦截）。
     */
    fun isForbidden(e: Throwable): Boolean {
        if (e is retrofit2.HttpException && e.code() == 403) return true
        val msg = e.message ?: return false
        return msg.contains("403")
    }

    /**
     * 包裹网络请求：先确保已过盾再执行 [block]；
     * 若执行过程中遇到 403,则强制刷新一次过盾并重试 [block] 一次。
     */
    suspend fun <T> withClearance(block: suspend () -> T): T {
        ensureClearance()
        return try {
            block()
        } catch (e: Throwable) {
            if (isForbidden(e)) {
                // cf_clearance 可能已失效,强制刷新后重试一次
                ensureClearance(forceRefresh = true)
                block()
            } else {
                throw e
            }
        }
    }
}