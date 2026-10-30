/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻译/语法状态机（第二阶段 S8）。
 *
 * 钉住设计 §7.3/§8 的六条硬约束：
 *  - 缓存命中 → 零请求；未命中 → 立即流式；
 *  - 第二次打开同一段 → 命中缓存（不再请求）；
 *  - 语法标签缓存命中 → 零请求；未命中 → 切到标签就请求（没有「开始分析」按钮）；
 *  - 同一 cacheKey 的并发请求去重；
 *  - 关闭取消请求，且不写半截结果；取消后迟到的结果既不进状态也不落库（含重新生成后的旧请求）；
 *  - 空译文按失败处理（`AiError.UNKNOWN`），不写缓存；
 *  - 重新生成覆盖同一键，错误只映射类别 + 状态码。
 */

package ua.acclorite.book_story.presentation.reader

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.data.ai.AI_PROMPT_VERSION
import ua.acclorite.book_story.data.ai.AiError
import ua.acclorite.book_story.data.ai.AiException
import ua.acclorite.book_story.data.ai.AiRequestConfig
import ua.acclorite.book_story.data.ai.AiRequestConfigProvider
import ua.acclorite.book_story.data.ai.AiTargetLanguage
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.ai.cache.CachedAiResult
import ua.acclorite.book_story.data.ai.cache.TranslationKind
import ua.acclorite.book_story.data.ai.cache.translationCacheKey
import ua.acclorite.book_story.presentation.reader.model.AsyncGrammar
import ua.acclorite.book_story.presentation.reader.model.AsyncText
import ua.acclorite.book_story.presentation.reader.model.TranslationTab

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val ai = mockk<AiTranslationRepository>()
    private val cache = FakeTranslationCache()
    private val configProvider = mockk<AiRequestConfigProvider>()

    private val config = AiRequestConfig(
        model = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1/",
        targetLanguage = AiTargetLanguage.SIMPLIFIED_CHINESE,
        targetLanguageName = "Simplified Chinese (zh-CN)",
        grammarEnabled = true
    )

    private val paragraph = "It was the best of times, it was the worst of times.\n" +
            "We had everything before us, we had nothing before us."

    private val translationKey = translationCacheKey(
        text = paragraph,
        kind = TranslationKind.TRANSLATION,
        targetLanguage = config.targetLanguageName,
        model = config.model,
        baseUrl = config.baseUrl
    )

    private val grammarKey = translationCacheKey(
        text = paragraph,
        kind = TranslationKind.GRAMMAR,
        targetLanguage = config.targetLanguageName,
        model = config.model,
        baseUrl = config.baseUrl
    )

    private val grammarJson = """
        {
          "summary": "两个并列的对照句。",
          "sentences": [
            {
              "original": "It was the best of times.",
              "translation": "那是最好的时代。",
              "structure": "主系表结构",
              "points": ["并列对照"]
            }
          ],
          "vocabulary": [
            {"word": "worst", "pos": "adj.", "meaning": "最坏的"}
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { configProvider.current() } returns config
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 模拟「不守规矩」的上游：`flow {}` 在取消时会被 `SafeCollector` 自己截停，而第三方实现
     * 完全可能把已经拿到的结果照样送完。用它钉住状态机自己的防线 —— `publish`
     * 的 `isActive`/`requestId` 校验、以及落库前的那次复查。生产里的 `ai.translate` 是 `flow {}`，
     * 所以这只是防御性用例，不代表线上真会出现迟到的那一块。
     */
    private class StubbornFlow(
        private val gate: CompletableDeferred<Unit>,
        private val before: String,
        private val after: String,
        private val emittedAfterCancel: AtomicBoolean
    ) : Flow<String> {
        override suspend fun collect(collector: FlowCollector<String>) {
            collector.emit(before)
            // 不响应取消的等待：dismiss/regenerate 之后仍然走完。
            withContext(NonCancellable) { gate.await() }
            collector.emit(after)
            emittedAfterCancel.set(true)
        }
    }

    /** 解析调度器换成测试调度器，否则 `advanceUntilIdle` 看不到真实线程上的解析结果。 */
    private fun model() = TranslationModel(ai, cache, configProvider).apply {
        parseDispatcher = dispatcher
    }

    private fun cacheTranslation(payload: String) {
        cache.entries[translationKey] = CachedAiResult(
            payload = payload,
            model = config.model,
            targetLanguage = config.targetLanguageName,
            createdAt = 1_000L
        )
    }

    private fun cacheGrammar(payload: String) {
        cache.entries[grammarKey] = CachedAiResult(
            payload = payload,
            model = config.model,
            targetLanguage = config.targetLanguageName,
            createdAt = 1_000L
        )
    }

    @Test
    fun `a cached translation is shown without any AI call`() = runTest(dispatcher) {
        cacheTranslation("那是最好的时代，也是最坏的时代。")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.visible)
        assertEquals(AsyncText.Success("那是最好的时代，也是最坏的时代。", fromCache = true), state.translation)
        verify(exactly = 0) { ai.translate(any(), any()) }
        coVerify(exactly = 0) { ai.analyzeGrammar(any(), any()) }
        assertTrue(cache.puts.isEmpty())
    }

    @Test
    fun `a cache miss streams the translation and stores the complete result`() = runTest(dispatcher) {
        every { ai.translate(paragraph, config.targetLanguage) } returns
                flowOf("那是最好的", "时代。")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(
            AsyncText.Success("那是最好的时代。", fromCache = false),
            model.state.value.translation
        )
        val put = cache.puts.single()
        assertEquals(translationKey, put.key)
        assertEquals(TranslationKind.TRANSLATION, put.kind)
        assertEquals(7, put.bookId)
        assertEquals(12, put.nodeId)
        assertEquals("那是最好的时代。", put.payload)
        assertEquals(config.model, put.model)
        assertEquals(config.baseUrl, put.baseUrl)
        assertEquals(config.targetLanguageName, put.targetLanguage)
        assertEquals(AI_PROMPT_VERSION, put.promptVersion)
    }

    @Test
    fun `opening the same paragraph twice hits the cache the second time`() = runTest(dispatcher) {
        every { ai.translate(paragraph, config.targetLanguage) } returns flowOf("译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        model.dismiss()
        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = true), model.state.value.translation)
        verify(exactly = 1) { ai.translate(any(), any()) }
    }

    @Test
    fun `a cached grammar analysis is shown on tab selection without a request`() = runTest(dispatcher) {
        cacheTranslation("译文")
        cacheGrammar(grammarJson)
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        model.selectTab(TranslationTab.GRAMMAR)
        advanceUntilIdle()

        val grammar = model.state.value.grammar as AsyncGrammar.Success
        assertTrue(grammar.fromCache)
        assertEquals("两个并列的对照句。", grammar.analysis?.summary)
        coVerify(exactly = 0) { ai.analyzeGrammar(any(), any()) }
    }

    @Test
    fun `a grammar cache miss is requested as soon as the tab is selected`() = runTest(dispatcher) {
        cacheTranslation("译文")
        coEvery { ai.analyzeGrammar(paragraph, config.targetLanguage) } returns Result.success(grammarJson)
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        coVerify(exactly = 0) { ai.analyzeGrammar(any(), any()) }

        model.selectTab(TranslationTab.GRAMMAR)
        advanceUntilIdle()

        val grammar = model.state.value.grammar as AsyncGrammar.Success
        assertFalse(grammar.fromCache)
        assertEquals(1, grammar.analysis?.sentences?.size)
        assertEquals("worst", grammar.analysis?.vocabulary?.firstOrNull()?.word)
        coVerify(exactly = 1) { ai.analyzeGrammar(paragraph, config.targetLanguage) }
        assertEquals(grammarKey, cache.puts.single().key)
        assertEquals(TranslationKind.GRAMMAR, cache.puts.single().kind)
    }

    @Test
    fun `repeated tab clicks do not duplicate an in-flight grammar request`() = runTest(dispatcher) {
        cacheTranslation("译文")
        val gate = CompletableDeferred<Unit>()
        coEvery { ai.analyzeGrammar(paragraph, config.targetLanguage) } coAnswers {
            gate.await()
            Result.success(grammarJson)
        }
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        model.selectTab(TranslationTab.GRAMMAR)
        runCurrent()
        model.selectTab(TranslationTab.GRAMMAR)
        model.selectTab(TranslationTab.TRANSLATION)
        model.selectTab(TranslationTab.GRAMMAR)
        runCurrent()

        coVerify(exactly = 1) { ai.analyzeGrammar(any(), any()) }

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.grammar is AsyncGrammar.Success)
    }

    @Test
    fun `dismissing cancels the request and never stores a partial result`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        every { ai.translate(paragraph, config.targetLanguage) } returns flow {
            emit("半截")
            gate.await()
            awaitCancellation()
        }
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        runCurrent()
        assertEquals(AsyncText.Streaming("半截"), model.state.value.translation)

        model.dismiss()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(model.state.value.visible)
        assertEquals(AsyncText.Idle, model.state.value.translation)
        assertTrue(cache.puts.isEmpty())
    }

    @Test
    fun `regenerate overwrites the same cache key`() = runTest(dispatcher) {
        every { ai.translate(paragraph, config.targetLanguage) } returnsMany listOf(
            flowOf("第一版"),
            flowOf("第二版")
        )
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        model.regenerate(TranslationTab.TRANSLATION)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("第二版", fromCache = false), model.state.value.translation)
        assertEquals(listOf("第一版", "第二版"), cache.puts.map { it.payload })
        // 重新生成不先删键：两次写的是同一个 cacheKey（`put` 覆盖）。
        assertEquals(1, cache.puts.map { it.key }.distinct().size)
        assertTrue(cache.puts.all { it.key == translationKey })
    }

    @Test
    fun `regenerate keeps the other tab untouched`() = runTest(dispatcher) {
        cacheTranslation("译文")
        coEvery { ai.analyzeGrammar(paragraph, config.targetLanguage) } returns Result.success(grammarJson)
        every { ai.translate(paragraph, config.targetLanguage) } returns flowOf("新译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        model.selectTab(TranslationTab.GRAMMAR)
        advanceUntilIdle()

        model.regenerate(TranslationTab.TRANSLATION)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("新译文", fromCache = false), model.state.value.translation)
        assertTrue(model.state.value.grammar is AsyncGrammar.Success)
    }

    @Test
    fun `ai errors are mapped to a category and status code`() = runTest(dispatcher) {
        every { ai.translate(paragraph, config.targetLanguage) } returns flow {
            throw AiException(AiError.RATE_LIMIT, 429)
        }
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(
            AsyncText.Error(AiError.RATE_LIMIT, 429),
            model.state.value.translation
        )
        assertTrue(cache.puts.isEmpty())
    }

    @Test
    fun `grammar errors are mapped and retry re-requests`() = runTest(dispatcher) {
        cacheTranslation("译文")
        coEvery { ai.analyzeGrammar(paragraph, config.targetLanguage) } returns
                Result.failure(AiException(AiError.NO_KEY, null))
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()
        model.selectTab(TranslationTab.GRAMMAR)
        advanceUntilIdle()

        assertEquals(AsyncGrammar.Error(AiError.NO_KEY, null), model.state.value.grammar)

        coEvery { ai.analyzeGrammar(paragraph, config.targetLanguage) } returns Result.success(grammarJson)
        model.regenerate(TranslationTab.GRAMMAR)
        advanceUntilIdle()

        assertTrue(model.state.value.grammar is AsyncGrammar.Success)
        coVerify(exactly = 2) { ai.analyzeGrammar(any(), any()) }
    }

    @Test
    fun `a cache write failure does not hide the finished translation`() = runTest(dispatcher) {
        cache.failPuts = true
        every { ai.translate(paragraph, config.targetLanguage) } returns flowOf("译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = false), model.state.value.translation)
    }

    @Test
    fun `a cache read failure still starts the request`() = runTest(dispatcher) {
        cache.failReads = true
        every { ai.translate(paragraph, config.targetLanguage) } returns flowOf("译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(AsyncText.Success("译文", fromCache = false), model.state.value.translation)
    }

    @Test
    fun `the whole paragraph is sent to the AI, not a page slice`() = runTest(dispatcher) {
        every { ai.translate(any(), any()) } returns flowOf("译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        verify(exactly = 1) { ai.translate(paragraph, config.targetLanguage) }
        assertEquals(paragraph, model.state.value.originalText)
        assertEquals(12, model.state.value.nodeId)
    }

    @Test
    fun `a selection without a paragraph is stored with the unknown node id`() = runTest(dispatcher) {
        every { ai.translate(any(), any()) } returns flowOf("译文")
        val model = model()

        model.open("selected words", bookId = 7, nodeId = null)
        advanceUntilIdle()

        assertEquals(-1, cache.puts.single().nodeId)
        assertEquals(null, model.state.value.nodeId)
    }

    @Test
    fun `a value emitted after dismiss never reaches the state or the cache`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val emittedAfterCancel = AtomicBoolean(false)
        every { ai.translate(paragraph, config.targetLanguage) } returns
                StubbornFlow(gate, before = "半截", after = "迟到", emittedAfterCancel)
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        runCurrent()
        assertEquals(AsyncText.Streaming("半截"), model.state.value.translation)

        model.dismiss()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("迟到的那一块确实发出来了", emittedAfterCancel.get())
        assertFalse(model.state.value.visible)
        assertEquals(AsyncText.Idle, model.state.value.translation)
        assertTrue(cache.puts.isEmpty())
    }

    @Test
    fun `a cancelled request cannot overwrite the cache after regenerate`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val staleEmitted = AtomicBoolean(false)
        every { ai.translate(paragraph, config.targetLanguage) } returnsMany listOf(
            StubbornFlow(gate, before = "半截", after = "旧译文", staleEmitted),
            flowOf("新译文")
        )
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        runCurrent()
        model.regenerate(TranslationTab.TRANSLATION)
        advanceUntilIdle()
        assertEquals(
            AsyncText.Success("新译文", fromCache = false),
            model.state.value.translation
        )

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("旧请求确实在重新生成之后才结束", staleEmitted.get())
        assertEquals(listOf("新译文"), cache.puts.map { it.payload })
        assertEquals(
            AsyncText.Success("新译文", fromCache = false),
            model.state.value.translation
        )
    }

    @Test
    fun `an empty translation becomes an unknown error and is never cached`() = runTest(dispatcher) {
        every { ai.translate(paragraph, config.targetLanguage) } returns flowOf("")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertEquals(AsyncText.Error(AiError.UNKNOWN, null), model.state.value.translation)
        assertTrue(cache.puts.isEmpty())
    }

    @Test
    fun `grammar json parsing is dispatched off the main dispatcher by default`() {
        assertSame(Dispatchers.Default, TranslationModel(ai, cache, configProvider).parseDispatcher)
    }

    @Test
    fun `blank text does not open the dialog`() = runTest(dispatcher) {
        val model = model()

        model.open("   ", bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertFalse(model.state.value.visible)
        verify(exactly = 0) { ai.translate(any(), any()) }
    }

    @Test
    fun `grammar is not requested while the grammar tab is disabled in settings`() = runTest(dispatcher) {
        every { configProvider.current() } returns config.copy(grammarEnabled = false)
        cacheTranslation("译文")
        val model = model()

        model.open(paragraph, bookId = 7, nodeId = 12)
        advanceUntilIdle()

        assertFalse(model.state.value.grammarAvailable)
        coVerify(exactly = 0) { ai.analyzeGrammar(any(), any()) }
    }
}
