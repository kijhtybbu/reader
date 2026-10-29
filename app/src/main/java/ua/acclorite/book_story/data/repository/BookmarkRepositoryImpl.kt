/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签仓库实现。
 */

package ua.acclorite.book_story.data.repository

import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.entity.BookmarkEntity
import ua.acclorite.book_story.domain.model.bookmark.Bookmark
import ua.acclorite.book_story.domain.repository.BookmarkRepository
import javax.inject.Inject

class BookmarkRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase
) : BookmarkRepository {

    override suspend fun getBookmarks(bookId: Int): Result<List<Bookmark>> = runCatching {
        appDatabase.bookmarkDao.getBookmarksForBook(bookId).map { it.toDomain() }
    }

    override suspend fun addBookmark(bookmark: Bookmark): Result<Unit> = runCatching {
        // fork: insertBookmark 用 OnConflictStrategy.IGNORE，撞上唯一索引时返回 -1 而不抛异常。
        // 不看这个返回值就把“没写进去”报成了成功，界面以为已收藏，列表里却没有。
        check(appDatabase.bookmarkDao.insertBookmark(bookmark.toEntity()) != -1L) {
            "Bookmark insert was ignored, a row at the same position already exists"
        }
    }

    override suspend fun moveBookmark(id: Int, bookmark: Bookmark): Result<Unit> = runCatching {
        check(appDatabase.bookmarkDao.moveBookmark(id, bookmark.toEntity()) != -1L) {
            "Bookmark move was ignored, a row at the same position already exists"
        }
    }

    override suspend fun deleteBookmark(id: Int): Result<Unit> = runCatching {
        appDatabase.bookmarkDao.deleteBookmark(id)
        Unit
    }

    override suspend fun deleteBookmarksForBook(bookId: Int): Result<Unit> = runCatching {
        appDatabase.bookmarkDao.deleteBookmarksForBook(bookId)
        Unit
    }
}

internal fun BookmarkEntity.toDomain() = Bookmark(
    id = id,
    bookId = bookId,
    nodeId = nodeId,
    nodeIntraOffset = nodeIntraOffset,
    chapterTitle = chapterTitle,
    snippet = snippet,
    createdAt = createdAt
)

internal fun Bookmark.toEntity() = BookmarkEntity(
    id = id,
    bookId = bookId,
    nodeId = nodeId,
    nodeIntraOffset = nodeIntraOffset,
    chapterTitle = chapterTitle,
    snippet = snippet,
    createdAt = createdAt
)
