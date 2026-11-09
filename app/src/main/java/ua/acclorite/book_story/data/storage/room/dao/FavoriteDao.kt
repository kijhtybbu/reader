/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹 DAO。
 *
 * 读路径全部返回 Flow：取词卡片里的五角星、收藏页列表、语法分析后的五角星都订阅同一行，
 * 在任意一处收藏/取消，另外两处立刻跟着变。
 */

package ua.acclorite.book_story.data.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.acclorite.book_story.data.storage.room.entity.FavoriteEntity

@Dao
interface FavoriteDao {

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'WORD'
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun observeWords(): Flow<List<FavoriteEntity>>

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'SENTENCE'
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun observeSentences(): Flow<List<FavoriteEntity>>

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'WORD' AND text = :text
        LIMIT 1
        """
    )
    fun observeWord(text: String): Flow<FavoriteEntity?>

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'SENTENCE' AND text = :text
        LIMIT 1
        """
    )
    fun observeSentence(text: String): Flow<FavoriteEntity?>

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'WORD' AND text = :text
        LIMIT 1
        """
    )
    suspend fun getWord(text: String): FavoriteEntity?

    @Query(
        """
        SELECT * FROM favoriteentity
        WHERE type = 'SENTENCE' AND text = :text
        LIMIT 1
        """
    )
    suspend fun getSentence(text: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity): Long

    @Query("DELETE FROM favoriteentity WHERE type = :type AND text = :text")
    suspend fun delete(type: String, text: String): Int

    /**
     * 句子收藏页第一次打开时才请求译文，成功后回填。
     *
     * 返回受影响行数：句子在请求期间被取消收藏时是 0 —— 调用方据此知道「译文没有写回去」，
     * 不会误以为已保存。
     */
    @Query(
        """
        UPDATE favoriteentity SET translation = :translation
        WHERE type = 'SENTENCE' AND text = :text
        """
    )
    suspend fun updateTranslation(text: String, translation: String): Int
}
