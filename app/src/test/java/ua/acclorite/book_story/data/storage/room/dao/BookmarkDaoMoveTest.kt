/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签移动的顺序。
 *
 * `moveBookmark` 的唯一硬约束是"插入没成功就绝不动旧行"。顺序写反（先删后插）时，
 * 一次唯一索引冲突就能让书签永久消失，而且日志里只会有一条无关紧要的失败。
 */

package ua.acclorite.book_story.data.storage.room.dao

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.data.storage.room.entity.BookmarkEntity

class BookmarkDaoMoveTest {

    private class FakeBookmarkDao(private val insertResult: Long) : BookmarkDao() {
        val deletedIds = mutableListOf<Int>()
        var insertedEntities = mutableListOf<BookmarkEntity>()

        override suspend fun getBookmarksForBook(bookId: Int): List<BookmarkEntity> = emptyList()

        override suspend fun insertBookmark(bookmark: BookmarkEntity): Long {
            insertedEntities.add(bookmark)
            return insertResult
        }

        override suspend fun deleteBookmark(id: Int): Int {
            deletedIds.add(id)
            return 1
        }

        override suspend fun deleteBookmarksForBook(bookId: Int): Int = 0
    }

    private val target = BookmarkEntity(
        bookId = 3,
        nodeId = 128,
        nodeIntraOffset = 42,
        chapterTitle = "第三章",
        snippet = "他合上书，窗外的雨还没停。",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `old row is deleted only after the new row was written`() = runTest {
        val dao = FakeBookmarkDao(insertResult = 12L)

        assertEquals(12L, dao.moveBookmark(id = 11, bookmark = target))
        assertEquals(listOf(target), dao.insertedEntities)
        assertEquals(listOf(11), dao.deletedIds)
    }

    @Test
    fun `ignored insert leaves the old bookmark in place`() = runTest {
        val dao = FakeBookmarkDao(insertResult = -1L)

        assertEquals(-1L, dao.moveBookmark(id = 11, bookmark = target))
        assertTrue(dao.deletedIds.isEmpty())
    }
}
