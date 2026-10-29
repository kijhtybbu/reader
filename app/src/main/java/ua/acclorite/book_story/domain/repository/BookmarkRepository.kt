/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签仓库。
 */

package ua.acclorite.book_story.domain.repository

import ua.acclorite.book_story.domain.model.bookmark.Bookmark

interface BookmarkRepository {
    suspend fun getBookmarks(bookId: Int): Result<List<Bookmark>>

    suspend fun addBookmark(bookmark: Bookmark): Result<Unit>

    suspend fun deleteBookmark(id: Int): Result<Unit>

    suspend fun deleteBookmarksForBook(bookId: Int): Result<Unit>
}
