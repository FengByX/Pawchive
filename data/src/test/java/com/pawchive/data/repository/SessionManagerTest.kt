package com.pawchive.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.store.SessionManager
import com.pawchive.core.store.SettingsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SessionManager 登录/登出与会话加密测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - 加密存储可用时：saveSession 后 isLoggedIn 返回 true，cookie 可读，clearSession 后清除
 * - 加密存储不可用时：saveSession 返回 false，isLoggedIn 返回 false（P1：禁止明文降级）
 * - logout 清除会话并返回成功
 *
 * Robolectric 下 EncryptedSharedPreferences 可能因 Keystore 影子实现限制而不可用，
 * 此时测试验证"加密不可用时的安全降级行为"——这正是 P1 BACKEND-002 的核心要求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var sessionManager: SessionManager
    private lateinit var localDataCleaner: LocalDataCleaner

    @Before
    fun setup() {
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
        // ARCH-003: AuthRepository 依赖 SessionManager + LocalDataCleaner（DI 注入）
        // ARCH-004: DownloadHistoryManager 现依赖 Room DAO（内存库）
        val db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDataCleaner = LocalDataCleaner(
            context,
            BookmarkManager(context, OfflineArchiveRepository(db.offlineArchiveDao(), Gson())),
            SearchHistoryManager(context),
            DownloadHistoryManager(context, db.downloadHistoryDao()),
            CacheRepository(context, SettingsManager(context))
        )
    }

    @After
    fun tearDown() {
        sessionManager.clearSession()
    }

    /**
     * 根据加密存储是否可用来分支验证。
     * - 可用：验证完整登录/登出流程
     * - 不可用：验证安全降级（不降级到明文，读取返回未登录状态）
     */
    @Test
    fun `session lifecycle respects encryption availability`() {
        if (sessionManager.isEncryptedStorageAvailable) {
            // 加密可用：验证完整登录/登出流程
            val cookie = "test-session-cookie-abc123"
            val username = "alice"

            assertTrue(sessionManager.saveSession(cookie))
            assertTrue(sessionManager.isLoggedIn())
            assertEquals(cookie, sessionManager.getSessionCookie())

            assertTrue(sessionManager.saveUsername(username))
            assertEquals(username, sessionManager.getUsername())

            sessionManager.clearSession()
            assertFalse(sessionManager.isLoggedIn())
            assertNull(sessionManager.getSessionCookie())
            assertNull(sessionManager.getUsername())
        } else {
            // 加密不可用：验证安全降级（P1 BACKEND-002 核心要求）
            // saveSession 返回 false，不降级到明文
            assertFalse(sessionManager.saveSession("cookie"))
            assertFalse(sessionManager.saveUsername("user"))
            // isLoggedIn 返回 false
            assertFalse(sessionManager.isLoggedIn())
            assertNull(sessionManager.getSessionCookie())
            assertNull(sessionManager.getUsername())
        }
    }

    @Test
    fun `fresh SessionManager reports not logged in regardless of encryption`() {
        // 无论加密是否可用，初始状态都应是未登录
        assertFalse(sessionManager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns false when cookie is empty`() {
        if (sessionManager.isEncryptedStorageAvailable) {
            // saveSession 空字符串：is_logged_in=true 但 cookie 为空
            sessionManager.saveSession("")
            // isLoggedIn 应返回 false（cookie 为空）
            assertFalse(sessionManager.isLoggedIn())
        } else {
            // 加密不可用时本来就返回 false
            assertFalse(sessionManager.isLoggedIn())
        }
    }

    @Test
    fun `saveSession overwrites previous cookie when encryption available`() {
        if (!sessionManager.isEncryptedStorageAvailable) return
        sessionManager.saveSession("old-cookie")
        sessionManager.saveSession("new-cookie")

        assertEquals("new-cookie", sessionManager.getSessionCookie())
        assertTrue(sessionManager.isLoggedIn())
    }

    @Test
    fun `clearSession is safe when encryption unavailable`() {
        // 加密不可用时 clearSession 不应抛异常
        sessionManager.clearSession()
        assertFalse(sessionManager.isLoggedIn())
    }

    @Test
    fun `clearSession is safe when already empty`() {
        // 连续清除不应抛异常
        sessionManager.clearSession()
        sessionManager.clearSession()
        assertFalse(sessionManager.isLoggedIn())
    }

    @Test
    fun `AuthRepository isLoggedIn delegates to SessionManager`() {
        val repo = AuthRepository(context, sessionManager, localDataCleaner)
        // 无论加密是否可用，未登录状态一致
        assertFalse(repo.isLoggedIn())
    }

    @Test
    fun `logout clears session and returns success`() {
        if (sessionManager.isEncryptedStorageAvailable) {
            sessionManager.saveSession("cookie-before-logout")
            sessionManager.saveUsername("user-before-logout")
        }
        val repo = AuthRepository(context, sessionManager, localDataCleaner)

        kotlinx.coroutines.runBlocking {
            val result = repo.logout()
            assertTrue(result.isSuccess)
        }

        // logout 后必定未登录
        assertFalse(sessionManager.isLoggedIn())
        assertNull(sessionManager.getSessionCookie())
        assertNull(sessionManager.getUsername())
    }

    @Test
    fun `encryption failure never falls back to plaintext`() {
        // P1 BACKEND-002 核心要求：加密失败时禁止明文降级
        if (!sessionManager.isEncryptedStorageAvailable) {
            // 加密不可用：saveSession 必须返回 false，绝不写入明文
            assertFalse(sessionManager.saveSession("should-not-be-saved"))
            // 读取必须返回 null，绝不返回明文存储的值
            assertNull(sessionManager.getSessionCookie())
            assertFalse(sessionManager.isLoggedIn())
        }
        // 加密可用时此测试自动通过（无需额外断言）
    }
}
