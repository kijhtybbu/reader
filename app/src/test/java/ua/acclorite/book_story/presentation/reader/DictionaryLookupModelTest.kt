/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片的状态机（第二阶段 S5）。
 *
 * 钉住四件事：
 *  - 先 Loading 再落地（Success / NotFound / Error）；
 *  - Success 一定带第一条命中的正文；
 *  - 切词条会重新取正文，而不是复用第一条；
 *  - 取消是「真的取消」：dismiss 之后迟到的结果不能再写回状态（否则卡片关了又自己弹出来）。
 */

package ua.acclorite.book_story.presentation.reader

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.data.dictionary.DictionaryHit
import ua.acclorite.book_story.data.dictionary.DictionaryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryLookupModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<DictionaryRepository>()

    private val apple = DictionaryHit(
        dictId = "builtin",
        dictName = "ECDICT",
        word = "apple",
        offset = 0L,
        size = 5,
        preview = "苹果",
        rank = DictionaryHit.RANK_EXACT
    )
    private val appleImported = apple.copy(dictId = "imported", dictName = "汉英", preview = "苹果（导入）")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lookup goes through loading and lands on the first hit`() = runTest(dispatcher) {
        // 用 gate 把仓库卡住，否则「Loading」这一帧在同一轮调度里就被 Success 覆盖掉了。
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.lookup("apple") } coAnswers {
            gate.await()
            listOf(apple, appleImported)
        }
        coEvery { repository.article("builtin", apple) } returns "<b>apple</b> 苹果"
        val model = DictionaryLookupModel(repository)

        model.lookup("apple", anchor = null)
        runCurrent()
        assertEquals(LookupState.Loading("apple", null), model.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
        val state = model.state.value as LookupState.Success
        assertEquals("apple", state.word)
        assertEquals(listOf(apple, appleImported), state.entries)
        assertEquals(0, state.selectedIndex)
        assertEquals("<b>apple</b> 苹果", state.html)
    }

    @Test
    fun `the word is normalized before it reaches the repository`() = runTest(dispatcher) {
        coEvery { repository.lookup("apple") } returns listOf(apple)
        coEvery { repository.article("builtin", apple) } returns "<b>apple</b>"
        val model = DictionaryLookupModel(repository)

        model.lookup("“apple,”", anchor = null)
        advanceUntilIdle()

        assertEquals("apple", (model.state.value as LookupState.Success).word)
    }

    @Test
    fun `no hits lands on not found`() = runTest(dispatcher) {
        coEvery { repository.lookup("zzz") } returns emptyList()
        val model = DictionaryLookupModel(repository)

        model.lookup("zzz", anchor = null)
        advanceUntilIdle()

        assertEquals(LookupState.NotFound("zzz", null), model.state.value)
    }

    /**
     * 这条不用 MockK：给 mock 的 suspend 函数塞一个抛异常的 answer 时，MockK 的协程
     * 永远不会把调用方的 continuation 恢复回去（状态会永远停在 Loading）。
     * 用真仓库 + 会抛异常的词典目录 lambda，异常在第一个 `withContext` 之前同步抛出，
     * 正好覆盖「仓库失败 → 错误态」这条路径，且不碰真实 IO。
     */
    @Test
    fun `a failing repository lands on error and retry re-runs the query`() = runTest(dispatcher) {
        var failing = true
        val failingRepository = DictionaryRepository(
            dictionaries = {
                if (failing) throw IllegalStateException("catalog gone") else emptyList()
            }
        )
        val model = DictionaryLookupModel(failingRepository)

        model.lookup("apple", anchor = null)
        advanceUntilIdle()
        val error = model.state.value as LookupState.Error
        assertEquals("apple", error.word)

        failing = false
        model.retry()
        advanceUntilIdle()

        assertEquals(LookupState.NotFound("apple", null), model.state.value)
    }

    @Test
    fun `selecting an entry loads that entry's article`() = runTest(dispatcher) {
        coEvery { repository.lookup("apple") } returns listOf(apple, appleImported)
        coEvery { repository.article("builtin", apple) } returns "<b>apple</b>"
        coEvery { repository.article("imported", appleImported) } returns "<b>apple</b> 导入"
        val model = DictionaryLookupModel(repository)
        model.lookup("apple", anchor = null)
        advanceUntilIdle()

        model.select(1)
        advanceUntilIdle()

        val state = model.state.value as LookupState.Success
        assertEquals(1, state.selectedIndex)
        assertEquals("<b>apple</b> 导入", state.html)
    }

    @Test
    fun `dismiss cancels the in-flight lookup and drops the late result`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.lookup("apple") } coAnswers {
            gate.await()
            listOf(apple)
        }
        val model = DictionaryLookupModel(repository)

        model.lookup("apple", anchor = null)
        runCurrent()
        assertTrue(model.state.value is LookupState.Loading)

        model.dismiss()
        assertEquals(LookupState.Idle, model.state.value)

        // 迟到的结果不能把卡片再弹出来
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LookupState.Idle, model.state.value)
    }

    @Test
    fun `a second lookup cancels the first one`() = runTest(dispatcher) {
        val firstGate = CompletableDeferred<Unit>()
        coEvery { repository.lookup("apple") } coAnswers {
            firstGate.await()
            listOf(apple)
        }
        coEvery { repository.lookup("banana") } returns listOf(apple.copy(word = "banana"))
        coEvery { repository.article(any(), any()) } returns "<b>banana</b>"
        val model = DictionaryLookupModel(repository)

        model.lookup("apple", anchor = null)
        runCurrent()
        model.lookup("banana", anchor = null)
        advanceUntilIdle()

        // 第一次的 Job 已被取消，即使它现在返回也不会覆盖第二次的结果
        firstGate.complete(Unit)
        advanceUntilIdle()

        val state = model.state.value as LookupState.Success
        assertEquals("banana", state.word)
    }

    @Test
    fun `punctuation only lookups are ignored`() = runTest(dispatcher) {
        val model = DictionaryLookupModel(repository)

        model.lookup("—", anchor = null)
        advanceUntilIdle()

        assertEquals(LookupState.Idle, model.state.value)
    }
}
