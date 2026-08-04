package com.pawchive.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppMemoryCache 内存缓存测试（BACKEND-009）。
 *
 * 覆盖核心场景：
 * - 基本读写：put/get 命中与未命中
 * - 覆盖写入：相同 key 覆盖旧值
 * - 清空缓存：clear 后所有条目不可读
 * - 类型安全：泛型强转
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppMemoryCacheTest {

    private val cache = AppMemoryCache.getInstance()

    @Before
    fun setup() {
        cache.clear()
    }

    @After
    fun tearDown() {
        cache.clear()
    }

    @Test
    fun `put then get returns cached value`() {
        cache.put("key1", "value1")
        val result: String? = cache.get("key1")
        assertEquals("value1", result)
    }

    @Test
    fun `get non-existent key returns null`() {
        val result: String? = cache.get("missing")
        assertNull(result)
    }

    @Test
    fun `put same key overwrites previous value`() {
        cache.put("key1", "old")
        cache.put("key1", "new")
        val result: String? = cache.get("key1")
        assertEquals("new", result)
    }

    @Test
    fun `clear removes all entries`() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.clear()
        assertNull(cache.get<String>("key1"))
        assertNull(cache.get<String>("key2"))
    }

    @Test
    fun `cache stores different types correctly`() {
        cache.put("string", "text")
        cache.put("int", 42)
        cache.put("bool", true)

        assertEquals("text", cache.get<String>("string"))
        assertEquals(42, cache.get<Int>("int"))
        assertEquals(true, cache.get<Boolean>("bool"))
    }

    @Test
    fun `multiple distinct keys all readable`() {
        for (i in 1..50) {
            cache.put("key$i", "value$i")
        }
        for (i in 1..50) {
            assertEquals("value$i", cache.get<String>("key$i"))
        }
    }
}
