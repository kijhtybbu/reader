/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 设置页的缓存管理（第二阶段 S9）。
 *
 * 只钉住设置页关心的状态：进页面读一次占用、清除成功后显示 0 条、读/清除失败各自有
 * 独立错误态；以及「按 MB 显示」的格式化不依赖设备 locale。
 */

package ua.acclorite.book_story.presentation.settings

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.data.ai.AiKeyStore
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.cache.CacheStats
import ua.acclorite.book_story.presentation.reader.FakeTranslationCache

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val keyStore = mockk<AiKeyStore>()
    private val ai = mockk<AiTranslationRepository>()
    private val cache = FakeTranslationCache()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { keyStore.get() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = AiSettingsModel(keyStore, ai, cache)

    @Test
    fun `cache stats are loaded when the screen opens`() = runTest(dispatcher) {
        cache.statsValue = CacheStats(count = 12, payloadBytes = 3L * 1024 * 1024)

        val model = model()
        advanceUntilIdle()

        assertEquals(
            AiCacheState.Ready(count = 12, payloadBytes = 3L * 1024 * 1024),
            model.cacheState.value
        )
    }

    @Test
    fun `a stats failure shows the load error`() = runTest(dispatcher) {
        cache.failStats = true

        val model = model()
        advanceUntilIdle()

        assertEquals(AiCacheState.LoadError, model.cacheState.value)
    }

    @Test
    fun `clearing the cache reports a cleared zero result`() = runTest(dispatcher) {
        cache.statsValue = CacheStats(count = 5, payloadBytes = 2048)
        val model = model()
        advanceUntilIdle()

        model.clearCache()
        advanceUntilIdle()

        assertEquals(1, cache.clearCalls)
        assertEquals(
            AiCacheState.Ready(count = 0, payloadBytes = 0, cleared = true),
            model.cacheState.value
        )
    }

    @Test
    fun `a clear failure shows the clear error`() = runTest(dispatcher) {
        cache.failClear = true
        val model = model()
        advanceUntilIdle()

        model.clearCache()
        advanceUntilIdle()

        assertEquals(AiCacheState.ClearError, model.cacheState.value)
    }

    @Test
    fun `cache payload size is formatted in MB`() {
        assertEquals("0.0 MB", formatCachePayloadSize(0))
        assertEquals("1.0 MB", formatCachePayloadSize(1024L * 1024))
        assertEquals("1.5 MB", formatCachePayloadSize(1024L * 1024 + 512L * 1024))
        assertEquals("200.0 MB", formatCachePayloadSize(200L * 1024 * 1024))
    }
}
