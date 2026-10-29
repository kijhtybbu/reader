/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签 DAO。
 */

package ua.acclorite.book_story.data.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.acclorite.book_story.data.storage.room.entity.BookmarkEntity

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarkentity WHERE bookId = :bookId ORDER BY nodeId, nodeIntraOffset")
    suspend fun getBookmarksForBook(bookId: Int): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarkentity WHERE id = :id")
    suspend fun deleteBookmark(id: Int): Int

    @Query("DELETE FROM bookmarkentity WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: Int): Int
}
