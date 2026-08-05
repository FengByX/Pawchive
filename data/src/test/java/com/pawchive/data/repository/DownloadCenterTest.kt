package com.pawchive.data.repository

import com.pawchive.core.model.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DownloadCenter 去重指纹测试（ARCH-005）。
 *
 * 验证 SHA-256 指纹语义：
 * - 相同"账号|url|文件名|类型"输入 → 相同指纹（确定性）
 * - 文件名不同 → 指纹不同（同 URL 不同文件不再误判为同一任务）
 * - 类型不同 → 指纹不同
 * - 账号不同 → 指纹不同（账号维度预留）
 * - 输出为 64 位十六进制
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCenterTest {

    private fun fp(
        url: String = "https://file.pawchive.pw/data/x.jpg",
        fileName: String = "x.jpg",
        mimeType: String = "image/jpeg",
        type: DownloadType = DownloadType.IMAGE,
        account: String = ""
    ) = DownloadCenter.dedupFingerprint(url, fileName, mimeType, type, account)

    @Test
    fun `same inputs produce same fingerprint`() {
        assertEquals(fp(), fp())
    }

    @Test
    fun `different fileName produces different fingerprint`() {
        // 同 URL 不同文件名 → 不同任务，指纹必须不同
        assertNotEquals(fp(fileName = "a.jpg"), fp(fileName = "b.jpg"))
    }

    @Test
    fun `different type produces different fingerprint`() {
        // 同 URL 同文件名，类型不同 → 不同任务
        assertNotEquals(
            fp(mimeType = "image/jpeg", type = DownloadType.IMAGE),
            fp(mimeType = "image/jpeg", type = DownloadType.ATTACHMENT)
        )
    }

    @Test
    fun `different account produces different fingerprint`() {
        assertNotEquals(fp(account = "userA"), fp(account = "userB"))
    }

    @Test
    fun `fingerprint is 64 lowercase hex chars`() {
        val value = fp()
        assertEquals(64, value.length)
        assertTrue(value.all { it.isDigit() || it in 'a'..'f' })
    }
}
