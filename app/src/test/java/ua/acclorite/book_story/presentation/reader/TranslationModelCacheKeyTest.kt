/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：TranslationModel 与 S7 缓存键的接线（第二阶段 S8，设计 §7.2/§7.4）。
 *
 * 状态机的单测用假缓存，会掩盖「模型自己拼了一份键」这种错误；这里直接断言模型读写的键
 * 等于 `translationCacheKey(text, kind, targetLanguage.promptName, model, baseUrl)`：
 *  - 同一段文字 + 同一配置 → 同一个键（第二次打开命中，零请求）；
 *  - 换模型 → 换键 → 必 miss（不会把旧模型的译文端上来）。
 */

package ua.acclorite.book_story.presentation.reader

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.data.ai.AI_PROMPT_VERSION
import ua.acclorite.book_story.data.ai.AiRequestConfig
import ua.acclorite.book_story.data.ai.AiRequestConfigProvider
import ua.acclorite.book_story.data.ai.AiTargetLanguage
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.cache.TranslationKind
import ua.acclorite.book_story.data.ai.cache.translationCacheKey
import ua.acclorite.book_story.presentation.reader.model.AsyncText

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationModelCacheKeyTest {

    private val dispatcher = StandardTestDispatcher()
    private val ai = mockk<AiTranslationRepository>()
    private val cache = FakeTranslationCache()
    private val configProvider = mockk<AiRequestConfigProvider>()

    private val text = "The quick brown fox jumps over the lazy dog."

    private val config = AiRequestConfig(
        model = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1/",
        targetLanguage = AiTargetLanguage.SIMPLIFIED_CHINESE,
        targetLanguageName = "Simplified Chinese (zh-CN)",
        grammarEnabled = true
    )

    private val expectedTranslationKey = translationCacheKey(
        text = text,
        kind = TranslationKind.TRANSLATION,
        targetLanguage = "Simplified Chinese (zh-CN)",
        model = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1/",
        promptVersion = AI_PROMPT_VERSION
    )

    private val expectedGrammarKey = translationCacheKey(
        text = text,
        kind = TranslationKind.GRAMMAR,
        targetLanguage = "Simplified Chinese (zh-CN)",
        model = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1/",
        promptVersion = AI_PROMPT_VERSION
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { configProvider.current() } returns config
        every { ai.translate(any(), any()) } returns flowOf("译文")
        coEvery { ai.analyzeGrammar(any(), any()) } returns Result.success("{}")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 语法解析的调度器换成测试调度器：真实线程上的解析会让 `advanceUntilIdle` 提前返回。 */
    private fun model() = TranslationModel(ai, cache, configProvider).apply {
        parseDispatcher = dispatcher
    }

    @Test
    fun `open reads the S7 keys for both kinds`() = runTest(dispatcher) {
        val model = model()

        model.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()

        assertEquals(listOf(expectedTranslationKey, expectedGrammarKey), cache.reads)
        assertEquals(expectedTranslationKey, cache.puts.single().key)
    }

    @Test
    fun `the same text and config hit the same key on the second open`() = runTest(dispatcher) {
        val model = model()

        model.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()
        model.dismiss()
        model.open(text, bookId = 9, nodeId = 40)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = true), model.state.value.translation)
        verify(exactly = 1) { ai.translate(any(), any()) }
    }

    @Test
    fun `a different model misses the cache`() = runTest(dispatcher) {
        val first = model()
        first.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()

        every { configProvider.current() } returns config.copy(model = "gpt-4.1-mini")
        val second = model()
        second.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = false), second.state.value.translation)
        verify(exactly = 2) { ai.translate(any(), any()) }
        assertEquals(2, cache.puts.map { it.key }.distinct().size)
    }

    @Test
    fun `a different target language misses the cache`() = runTest(dispatcher) {
        val first = model()
        first.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()

        every { configProvider.current() } returns config.copy(
            targetLanguage = AiTargetLanguage.ENGLISH,
            targetLanguageName = "English (en)"
        )
        val second = model()
        second.open(text, bookId = 1, nodeId = 2)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = false), second.state.value.translation)
        assertTrue(cache.puts.map { it.key }.distinct().size == 2)
    }
}
