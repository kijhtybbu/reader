/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签仓库的写路径。
 *
 * 这两条断言盯的是同一个旧 bug：`insertBookmark` 用 OnConflictStrategy.IGNORE，
 * 撞唯一索引时返回 -1 而不是抛异常，曾经被当成"写成功了"报给界面。
 */

package ua.acclorite.book_story.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.dao.BookmarkDao
import ua.acclorite.book_story.domain.model.bookmark.Bookmark

class BookmarkRepositoryImplTest {

    private val dao = mockk<BookmarkDao>()
    private val repository = BookmarkRepositoryImpl(
        mockk<AppDatabase> {
            every { bookmarkDao } returns dao
        }
    )

    private val bookmark = Bookmark(
        bookId = 3,
        nodeId = 128,
        nodeIntraOffset = 42,
        chapterTitle = "第三章",
        snippet = "他合上书，窗外的雨还没停。",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `add fails when room ignores the insert because of the unique index`() = runTest {
        coEvery { dao.insertBookmark(any()) } returns -1L

        assertTrue(repository.addBookmark(bookmark).isFailure)
    }

    @Test
    fun `add succeeds when room writes the row`() = runTest {
        coEvery { dao.insertBookmark(any()) } returns 11L

        assertTrue(repository.addBookmark(bookmark).isSuccess)
    }

    @Test
    fun `move fails when room ignores the insert instead of dropping the old bookmark`() = runTest {
        coEvery { dao.moveBookmark(any(), any()) } returns -1L

        assertTrue(repository.moveBookmark(id = 11, bookmark = bookmark).isFailure)
    }

    @Test
    fun `move succeeds when the bookmark lands on the new row`() = runTest {
        coEvery { dao.moveBookmark(any(), any()) } returns 12L

        assertTrue(repository.moveBookmark(id = 11, bookmark = bookmark).isSuccess)
    }
}
