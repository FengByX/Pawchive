package com.pawchive.data.github

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.store.SettingsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UpdateChecker 版本比较测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - 数字版本号比较：1.0.10 > 1.0.9
 * - 段数不一致：1.2 > 1.2.0
 * - 预发布后缀：1.2.0-beta < 1.2.0，1.2.0-rc.1 < 1.2.0
 * - v 前缀剥离（由调用方处理，比较函数接收已去前缀版本）
 * - 相同版本不算更新
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var checker: UpdateChecker

    @Before
    fun setup() {
        checker = UpdateChecker(context, SettingsManager(context))
    }

    @Test
    fun `patch version 10 is newer than 9`() {
        assertTrue(checker.isNewerVersion("1.0.10", "1.0.9"))
    }

    @Test
    fun `minor version increase is newer`() {
        assertTrue(checker.isNewerVersion("1.1.0", "1.0.9"))
    }

    @Test
    fun `major version increase is newer`() {
        assertTrue(checker.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(checker.isNewerVersion("1.2.3", "1.2.3"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(checker.isNewerVersion("1.0.0", "1.2.0"))
    }

    @Test
    fun `fewer segments padded with zero`() {
        // 1.2 vs 1.2.0 → 1.2.0 == 1.2.0，不算更新
        assertFalse(checker.isNewerVersion("1.2", "1.2.0"))
    }

    @Test
    fun `fewer segments with higher minor`() {
        // 1.3 vs 1.2.5 → 1.3.0 > 1.2.5
        assertTrue(checker.isNewerVersion("1.3", "1.2.5"))
    }

    @Test
    fun `prerelease beta is older than release`() {
        // latest=1.2.0-beta, current=1.2.0 → beta 不算更新
        assertFalse(checker.isNewerVersion("1.2.0-beta", "1.2.0"))
    }

    @Test
    fun `release is newer than prerelease`() {
        // latest=1.2.0, current=1.2.0-beta → 正式版算更新
        assertTrue(checker.isNewerVersion("1.2.0", "1.2.0-beta"))
    }

    @Test
    fun `prerelease rc1 is older than rc2`() {
        // 字符串比较：rc.1 < rc.2
        assertTrue(checker.isNewerVersion("1.2.0-rc.2", "1.2.0-rc.1"))
    }

    @Test
    fun `prerelease same suffix is not newer`() {
        assertFalse(checker.isNewerVersion("1.2.0-beta", "1.2.0-beta"))
    }

    @Test
    fun `higher numeric version beats prerelease`() {
        // 1.3.0-beta vs 1.2.0 → 数字部分 1.3 > 1.2，算更新
        assertTrue(checker.isNewerVersion("1.3.0-beta", "1.2.0"))
    }

    @Test
    fun `version 1_5_0 vs 1_4_9`() {
        // 实际项目版本场景
        assertTrue(checker.isNewerVersion("1.5.0", "1.4.9"))
    }

    @Test
    fun `version 1_4_9 vs 1_4_8`() {
        assertTrue(checker.isNewerVersion("1.4.9", "1.4.8"))
    }

    @Test
    fun `version 1_4_9 vs 1_4_9 same`() {
        assertFalse(checker.isNewerVersion("1.4.9", "1.4.9"))
    }

    @Test
    fun `version with v prefix handled by caller`() {
        // isNewerVersion 不处理 v 前缀，调用方需先 removePrefix("v")
        // 这里传入已处理过的版本号
        val latest = "1.5.0"
        val current = "1.4.9"
        assertTrue(checker.isNewerVersion(latest, current))
    }

    @Test
    fun `single segment versions compare correctly`() {
        assertTrue(checker.isNewerVersion("2", "1"))
        assertFalse(checker.isNewerVersion("1", "2"))
        assertFalse(checker.isNewerVersion("1", "1"))
    }

    @Test
    fun `four segment versions compare correctly`() {
        assertTrue(checker.isNewerVersion("1.2.3.4", "1.2.3.3"))
        assertFalse(checker.isNewerVersion("1.2.3.3", "1.2.3.4"))
    }
}
