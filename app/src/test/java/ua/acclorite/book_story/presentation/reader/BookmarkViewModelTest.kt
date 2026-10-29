/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：切换书籍时的书签隔离。
 *
 * BookmarkViewModel 是 Activity 作用域（ReaderContent 里 hiltViewModel()），
 * 打开另一本书时它不会重建，_bookmarks 里还是上一本书的列表。这两条断言钉住的是：
 * 1. 切书当场就清空，不等异步读库；
 * 2. 上一本书那次尚未完成的刷新回来后不能把列表写回去。
 */

package ua.acclorite.book_story.presentation.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.domain.repository.BookmarkRepository

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 读库被 gate 卡住，直到测试显式放行 —— 这样才能把「上一本书的结果晚到」复现出来。
     */
    private class GatedBookmarkRepository : BookmarkRepository {

        private val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        private fun gate(bookId: Int) = gates.getOrPut(bookId) { CompletableDeferred() }

        fun release(bookId: Int) {
            gate(bookId).complete(Unit)
        }

        override suspend fun getBookmarks(bookId: Int): Result<List<Bookmark>> {
            gate(bookId).await()
            return Result.success(
                listOf(
                    Bookmark(
                        id = bookId,
                        bookId = bookId,
                        nodeId = 1,
                        nodeIntraOffset = 0,
                        chapterTitle = "chapter of $bookId",
                        snippet = "snippet",
                        createdAt = 0L
                    )
                )
            )
        }

        override suspend fun addBookmark(bookmark: Bookmark): Result<Unit> = Result.success(Unit)

        override suspend fun moveBookmark(id: Int, bookmark: Bookmark): Result<Unit> =
            Result.success(Unit)

        override suspend fun deleteBookmark(id: Int): Result<Unit> = Result.success(Unit)

        override suspend fun deleteBookmarksForBook(bookId: Int): Result<Unit> =
            Result.success(Unit)
    }

    @Test
    fun `switching books clears the list synchronously`() = runTest(dispatcher) {
        val repository = GatedBookmarkRepository()
        val viewModel = BookmarkViewModel(repository)

        viewModel.load(1)
        runCurrent()
        repository.release(1)
        runCurrent()
        assertEquals(listOf(1), viewModel.bookmarks.value.map { it.bookId })

        // 关键：不推进调度器，切书当场就应该看到空列表
        viewModel.load(2)
        assertTrue(viewModel.bookmarks.value.isEmpty())
    }

    @Test
    fun `a late result for the previous book is discarded`() = runTest(dispatcher) {
        val repository = GatedBookmarkRepository()
        val viewModel = BookmarkViewModel(repository)

        // A 书的刷新已经进锁并挂起在读库上
        viewModel.load(1)
        runCurrent()

        // 此时切到 B 书：清空 + 起一个新的刷新（它会排在锁后面）
        viewModel.load(2)
        assertTrue(viewModel.bookmarks.value.isEmpty())

        // A 书的结果现在才回来 —— 不能落进列表
        repository.release(1)
        runCurrent()
        assertTrue(viewModel.bookmarks.value.isEmpty())

        // B 书自己读完后才允许出现 B 的书签
        repository.release(2)
        runCurrent()
        assertEquals(listOf(2), viewModel.bookmarks.value.map { it.bookId })
    }
}
