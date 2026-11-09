/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹仓库实现。
 *
 * 归一规则收在这里，而不是散在三个调用点：
 *  - 单词：trim + 小写。词典词头本身是小写，`Apple` 与 `apple` 必须命中同一条收藏。
 *  - 句子：trim。句子内部的大小写/标点是内容的一部分，不能改。
 * 归一函数是 `internal`，单测直接覆盖（大小写、首尾空白、不同写法只留一条）。
 */

package ua.acclorite.book_story.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.entity.FavoriteEntity
import ua.acclorite.book_story.data.storage.room.entity.FavoriteEntityType
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord
import ua.acclorite.book_story.domain.repository.FavoriteRepository
import java.util.Locale
import javax.inject.Inject

/** 单词收藏的键：首尾空白不参与比较，大小写不敏感。 */
internal fun favoriteWordKey(word: String): String =
    word.trim().lowercase(Locale.ROOT)

/** 句子收藏的键：只去掉首尾空白，保留句子内部的一切。 */
internal fun favoriteSentenceKey(sentence: String): String = sentence.trim()

class FavoriteRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : FavoriteRepository {

    /** 现取 DAO：`AppDatabase` 是懒打开的，构造期访问 DAO 会在数据库还没打开时初始化它。 */
    private val dao get() = database.favoriteDao

    override fun observeWords(): Flow<List<FavoriteWord>> =
        dao.observeWords().map { rows -> rows.map { it.toFavoriteWord() } }

    override fun observeSentences(): Flow<List<FavoriteSentence>> =
        dao.observeSentences().map { rows -> rows.map { it.toFavoriteSentence() } }

    override fun observeWord(word: String): Flow<Boolean> =
        dao.observeWord(favoriteWordKey(word)).map { it != null }

    override fun observeSentence(sentence: String): Flow<Boolean> =
        dao.observeSentence(favoriteSentenceKey(sentence)).map { it != null }

    override suspend fun getWord(word: String): FavoriteWord? =
        dao.getWord(favoriteWordKey(word))?.toFavoriteWord()

    override suspend fun getSentence(sentence: String): FavoriteSentence? =
        dao.getSentence(favoriteSentenceKey(sentence))?.toFavoriteSentence()

    override suspend fun addWord(word: String, phonetic: String?, definition: String?) {
        val key = favoriteWordKey(word)
        if (key.isEmpty()) return
        dao.insert(
            FavoriteEntity(
                type = FavoriteEntityType.WORD,
                text = key,
                phonetic = phonetic?.trim()?.ifEmpty { null },
                definition = definition?.trim()?.ifEmpty { null },
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun removeWord(word: String) {
        dao.delete(FavoriteEntityType.WORD, favoriteWordKey(word))
    }

    override suspend fun addSentence(text: String, translation: String?, grammar: String?) {
        val key = favoriteSentenceKey(text)
        if (key.isEmpty()) return
        dao.insert(
            FavoriteEntity(
                type = FavoriteEntityType.SENTENCE,
                text = key,
                translation = translation?.ifBlank { null },
                grammar = grammar?.ifBlank { null },
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun removeSentence(text: String) {
        dao.delete(FavoriteEntityType.SENTENCE, favoriteSentenceKey(text))
    }

    override suspend fun updateSentenceTranslation(text: String, translation: String): Boolean =
        dao.updateTranslation(favoriteSentenceKey(text), translation) > 0
}

internal fun FavoriteEntity.toFavoriteWord() = FavoriteWord(
    id = id,
    word = text,
    phonetic = phonetic,
    definition = definition,
    createdAt = createdAt
)

internal fun FavoriteEntity.toFavoriteSentence() = FavoriteSentence(
    id = id,
    text = text,
    translation = translation,
    grammar = grammar,
    createdAt = createdAt
)
