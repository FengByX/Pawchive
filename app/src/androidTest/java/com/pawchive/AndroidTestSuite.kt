package com.pawchive

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * androidTest 目录占位测试（BACKEND-009）。
 *
 * Acceptance 要求 `app/src/androidTest` 目录存在且 CI 可运行。
 * 此测试验证应用 Context 可获取，作为插桩测试基础设施的冒烟测试。
 * 后续可在此目录补充需要真机/模拟器的 UI 与集成测试。
 */
@RunWith(AndroidJUnit4::class)
class AndroidTestSuite {

    @Test
    fun `application context is available`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotNull(context)
        assertNotNull(context.packageName)
    }
}
