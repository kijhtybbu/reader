/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹仓库。
 *
 * 收藏是**全局**的（不属于某本书）：同一个生词在不同书里出现时只保留一条，
 * 这也符合「生词本」的直觉。
 *
 * 读路径全部是 Flow：取词卡片、语法分析结果框、收藏页三处订阅同一行数据，
 * 任意一处切换收藏状态，其余两处立即同步。
 */

package ua.acclorite.book_story.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord

interface FavoriteRepository {

    fun observeWords(): Flow<List<FavoriteWord>>

    fun observeSentences(): Flow<List<FavoriteSentence>>

    /** 这个词在收藏夹里吗。词头在实现里统一归一，大小写不同也命中同一条。 */
    fun observeWord(word: String): Flow<Boolean>

    /** 这句话在收藏夹里吗。句子按 trim 后的原文匹配。 */
    fun observeSentence(sentence: String): Flow<Boolean>

    suspend fun getWord(word: String): FavoriteWord?

    suspend fun getSentence(sentence: String): FavoriteSentence?

    suspend fun addWord(word: String, phonetic: String?, definition: String?)

    suspend fun removeWord(word: String)

    suspend fun addSentence(text: String, translation: String?, grammar: String?)

    suspend fun removeSentence(text: String)

    /** 收藏页补到译文后回填；句子已被取消收藏时返回 false（没有写回任何行）。 */
    suspend fun updateSentenceTranslation(text: String, translation: String): Boolean
}
