/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏状态机的关键路径（切换收藏、按需补译文、晚到结果丢弃）。
 *
 * 这几条都是「错了也不会崩溃、但用户会看到错误内容」的路径：
 *  - 连点五角星必须真的在「已收藏/未收藏」之间切换，不能变成两次写入；
 *  - 已有译文时打开句子不能偷偷再请求一次（费钱且慢）；
 *  - 快速从句子 A 切到 B 时，A 的迟到译文不能覆盖 B 的详情。
 */

package ua.acclorite.book_story.presentation.favorites

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.data.ai.AiRequestConfig
import ua.acclorite.book_story.data.ai.AiRequestConfigProvider
import ua.acclorite.book_story.data.ai.AiTargetLanguage
import ua.acclorite.book_story.data.ai.AiTranslationRepository
import ua.acclorite.book_story.data.repository.favoriteSentenceKey
import ua.acclorite.book_story.data.repository.favoriteWordKey
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord
import ua.acclorite.book_story.domain.model.favorite.FavoriteWordDraft
import ua.acclorite.book_story.domain.repository.FavoriteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(
        repository: FakeFavoriteRepository,
        ai: AiTranslationRepository = mockk(relaxed = true)
    ): FavoritesModel {
        val config = mockk<AiRequestConfigProvider>()
        every { config.current() } returns AiRequestConfig(
            model = "test-model",
            baseUrl = "https://example.test/v1/",
            targetLanguage = AiTargetLanguage.ENGLISH,
            targetLanguageName = "English (en)",
            grammarEnabled = true
        )
        return FavoritesModel(repository, ai, config)
    }

    @Test
    fun `toggling a word adds it then removes it`() = runTest(dispatcher) {
        val repository = FakeFavoriteRepository()
        val viewModel = model(repository)

        viewModel.toggleWord(FavoriteWordDraft(" Apple ", "/ˈæpl/", "n. 苹果"))
        runCurrent()
        assertEquals(listOf("apple"), repository.words.value.map { it.word })

        viewModel.toggleWord(FavoriteWordDraft("apple", null, null))
        runCurrent()
        assertTrue(repository.words.value.isEmpty())
    }

    @Test
    fun `two rapid taps still end in the removed state`() = runTest(dispatcher) {
        val repository = FakeFavoriteRepository()
        // 读库在读到结果后挂起一次：没有 Mutex 时两个协程都会读到「未收藏」，
        // 于是各写一次（最终仍是已收藏）；有 Mutex 时第二次必须等第一次落库。
        repository.yieldOnGetWord = true
        val viewModel = model(repository)

        viewModel.toggleWord(FavoriteWordDraft("apple", null, null))
        viewModel.toggleWord(FavoriteWordDraft("apple", null, null))
        runCurrent()

        assertTrue(repository.words.value.isEmpty())
    }

    @Test
    fun `opening a sentence with a stored translation makes no request`() = runTest(dispatcher) {
        val repository = FakeFavoriteRepository()
        repository.addSentence("It works.", "已有译文", "**grammar**")
        val ai = mockk<AiTranslationRepository>(relaxed = true)
        val viewModel = model(repository, ai)

        viewModel.openSentence(" It works. ")
        runCurrent()

        assertEquals(
            FavoriteTranslation.Success("已有译文"),
            viewModel.sentenceDialog.value?.translation
        )
        verify(exactly = 0) { ai.translate(any(), any(), any()) }
    }

    @Test
    fun `opening a sentence without a translation requests one and stores it`() =
        runTest(dispatcher) {
            val repository = FakeFavoriteRepository()
            repository.addSentence("It works.", null, "**grammar**")
            val ai = mockk<AiTranslationRepository>(relaxed = true)
            every { ai.translate(any(), any(), any()) } returns flowOf("译文")
            val viewModel = model(repository, ai)

            viewModel.openSentence("It works.")
            runCurrent()

            assertEquals(
                FavoriteTranslation.Success("译文"),
                viewModel.sentenceDialog.value?.translation
            )
            assertEquals("译文", repository.translationOf("It works."))
        }

    @Test
    fun `opening another sentence before the first loads keeps the second`() =
        runTest(dispatcher) {
            val repository = FakeFavoriteRepository()
            repository.addSentence("First.", null, "g1")
            repository.addSentence("Second.", null, "g2")
            // 卡住 First. 的读库：模拟慢数据库时用户已经切到第二句。
            val gate = repository.gateSentence("First.")
            val ai = mockk<AiTranslationRepository>(relaxed = true)
            every { ai.translate(eq("Second."), any(), any()) } returns flowOf("Second translation")
            val viewModel = model(repository, ai)

            viewModel.openSentence("First.")
            runCurrent()
            viewModel.openSentence("Second.")
            runCurrent()
            assertEquals(
                FavoriteTranslation.Success("Second translation"),
                viewModel.sentenceDialog.value?.translation
            )

            // First. 的读库现在才回来：openSentenceKey 已经变了，不能把详情换回第一句。
            gate.complete(Unit)
            runCurrent()
            assertEquals("Second.", viewModel.sentenceDialog.value?.favorite?.text)
            assertEquals(
                FavoriteTranslation.Success("Second translation"),
                viewModel.sentenceDialog.value?.translation
            )
        }

    @Test
    fun `grammar from a later analysis is written back to an existing favorite`() =
        runTest(dispatcher) {
            val repository = FakeFavoriteRepository()
            repository.addSentence("It works.", null, null)
            val viewModel = model(repository)

            viewModel.updateSentenceGrammar(" It works. ", "**grammar**")
            runCurrent()

            assertEquals("**grammar**", repository.grammarOf("It works."))
        }

    @Test
    fun `grammar update does not create a row when the sentence is not favorited`() =
        runTest(dispatcher) {
            val repository = FakeFavoriteRepository()
            val viewModel = model(repository)

            viewModel.updateSentenceGrammar("It works.", "**grammar**")
            runCurrent()

            assertTrue(repository.sentences.value.isEmpty())
        }

    @Test
    fun `closing the dialog clears the state`() = runTest(dispatcher) {
        val repository = FakeFavoriteRepository()
        repository.addSentence("It works.", "已有译文", "**grammar**")
        val viewModel = model(repository)

        viewModel.openSentence("It works.")
        runCurrent()
        viewModel.closeSentence()

        assertEquals(null, viewModel.sentenceDialog.value)
    }

    /** 内存版收藏仓库：只实现状态机用到的路径。 */
    private class FakeFavoriteRepository : FavoriteRepository {

        private val nextId = java.util.concurrent.atomic.AtomicLong(1)

        val words = MutableStateFlow<List<FavoriteWord>>(emptyList())
        val sentences = MutableStateFlow<List<FavoriteSentence>>(emptyList())

        /** 打开单词收藏前的读取是否在拿到结果后挂起一次（模拟并发竞争窗口）。 */
        var yieldOnGetWord = false

        private val sentenceGates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** 卡住某一句的读库；用来复现「读库慢、用户已经切到下一句」。 */
        fun gateSentence(text: String): CompletableDeferred<Unit> =
            sentenceGates.getOrPut(favoriteSentenceKey(text)) { CompletableDeferred() }

        override fun observeWords(): Flow<List<FavoriteWord>> = words

        override fun observeSentences(): Flow<List<FavoriteSentence>> = sentences

        override fun observeWord(word: String): Flow<Boolean> = words.map { rows ->
            rows.any { it.word == favoriteWordKey(word) }
        }

        override fun observeSentence(sentence: String): Flow<Boolean> = sentences.map { rows ->
            rows.any { it.text == favoriteSentenceKey(sentence) }
        }

        override suspend fun getWord(word: String): FavoriteWord? {
            val row = words.value.firstOrNull { it.word == favoriteWordKey(word) }
            if (yieldOnGetWord) yield()
            return row
        }

        override suspend fun getSentence(sentence: String): FavoriteSentence? {
            val key = favoriteSentenceKey(sentence)
            sentenceGates[key]?.await()
            return sentences.value.firstOrNull { it.text == key }
        }

        override suspend fun addWord(word: String, phonetic: String?, definition: String?) {
            words.value = listOf(
                FavoriteWord(
                    id = nextId.getAndIncrement(),
                    word = favoriteWordKey(word),
                    phonetic = phonetic,
                    definition = definition,
                    createdAt = 0L
                )
            ) + words.value
        }

        override suspend fun removeWord(word: String) {
            words.value = words.value.filterNot { it.word == favoriteWordKey(word) }
        }

        override suspend fun addSentence(text: String, translation: String?, grammar: String?) {
            sentences.value = listOf(
                FavoriteSentence(
                    id = nextId.getAndIncrement(),
                    text = favoriteSentenceKey(text),
                    translation = translation,
                    grammar = grammar,
                    createdAt = 0L
                )
            ) + sentences.value
        }

        override suspend fun removeSentence(text: String) {
            sentences.value = sentences.value.filterNot { it.text == favoriteSentenceKey(text) }
        }

        override suspend fun updateSentenceTranslation(text: String, translation: String): Boolean {
            val key = favoriteSentenceKey(text)
            if (sentences.value.none { it.text == key }) return false
            sentences.value = sentences.value.map {
                if (it.text == key) it.copy(translation = translation) else it
            }
            return true
        }

        override suspend fun updateSentenceGrammar(text: String, grammar: String): Boolean {
            val key = favoriteSentenceKey(text)
            if (sentences.value.none { it.text == key }) return false
            sentences.value = sentences.value.map {
                if (it.text == key) it.copy(grammar = grammar) else it
            }
            return true
        }

        fun translationOf(text: String): String? =
            sentences.value.firstOrNull { it.text == favoriteSentenceKey(text) }?.translation

        fun grammarOf(text: String): String? =
            sentences.value.firstOrNull { it.text == favoriteSentenceKey(text) }?.grammar
    }
}
