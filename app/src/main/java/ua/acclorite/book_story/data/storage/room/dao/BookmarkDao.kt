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
import androidx.room.Transaction
import ua.acclorite.book_story.data.storage.room.entity.BookmarkEntity

// fork: 用 abstract class 而不是 interface —— [moveBookmark] 需要带方法体，
// 而 Room 对 Kotlin 接口默认方法（DefaultImpls）生成事务包装器并不可靠，抽象类的具体方法没有这个问题。
@Dao
abstract class BookmarkDao {
    @Query("SELECT * FROM bookmarkentity WHERE bookId = :bookId ORDER BY nodeId, nodeIntraOffset")
    abstract suspend fun getBookmarksForBook(bookId: Int): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarkentity WHERE id = :id")
    abstract suspend fun deleteBookmark(id: Int): Int

    @Query("DELETE FROM bookmarkentity WHERE bookId = :bookId")
    abstract suspend fun deleteBookmarksForBook(bookId: Int): Int

    /**
     * fork 新增：把书签 [id] 挪到 [bookmark] 的位置。
     *
     * 必须在一个事务里，且**先插后删**：先删后插一旦插入失败（唯一索引冲突），书签就永久丢了。
     * 插入返回 -1（`OnConflictStrategy.IGNORE` 吃掉冲突）时不动旧行并原样返回 -1，调用方据此报失败。
     * 新位置的 (bookId, nodeId, nodeIntraOffset) 与旧行不同，所以插入不会撞上旧行自己的唯一索引。
     *
     * @return 新行的 row id，冲突时 -1。
     */
    @Transaction
    open suspend fun moveBookmark(id: Int, bookmark: BookmarkEntity): Long {
        val insertedId = insertBookmark(bookmark)
        if (insertedId == -1L) return -1L

        deleteBookmark(id)
        return insertedId
    }
}
