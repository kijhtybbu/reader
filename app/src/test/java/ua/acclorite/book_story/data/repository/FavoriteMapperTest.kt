/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：收藏夹的映射与归一。
 *
 * 两条最容易出错的规则：
 *  1. 单词必须大小写不敏感（`Apple` / `apple` 是同一条），句子只能 trim（大小写是内容的一部分）；
 *  2. 实体与领域模型来回转换时不能漏字段 —— 漏掉 phonetic，收藏页的音标就会永远是空的。
 */

package ua.acclorite.book_story.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.acclorite.book_story.data.storage.room.AppDatabase
import ua.acclorite.book_story.data.storage.room.dao.FavoriteDao
import ua.acclorite.book_story.data.storage.room.entity.FavoriteEntity
import ua.acclorite.book_story.data.storage.room.entity.FavoriteEntityType
import ua.acclorite.book_story.domain.model.favorite.FavoriteSentence
import ua.acclorite.book_story.domain.model.favorite.FavoriteWord

class FavoriteMapperTest {

    private val wordEntity = FavoriteEntity(
        id = 7,
        type = FavoriteEntityType.WORD,
        text = "apple",
        phonetic = "/ˈæpl/",
        definition = "n. 苹果",
        createdAt = 1_700_000_000_000L
    )

    private val sentenceEntity = FavoriteEntity(
        id = 9,
        type = FavoriteEntityType.SENTENCE,
        text = "The apple falls far from the tree.",
        translation = "苹果掉在离树很远的地方。",
        grammar = "**主语** The apple",
        createdAt = 1_700_000_000_001L
    )

    @Test
    fun `word round trip keeps every field`() {
        assertEquals(
            FavoriteWord(
                id = 7,
                word = "apple",
                phonetic = "/ˈæpl/",
                definition = "n. 苹果",
                createdAt = 1_700_000_000_000L
            ),
            wordEntity.toFavoriteWord()
        )
    }

    @Test
    fun `sentence round trip keeps translation and grammar`() {
        assertEquals(
            FavoriteSentence(
                id = 9,
                text = "The apple falls far from the tree.",
                translation = "苹果掉在离树很远的地方。",
                grammar = "**主语** The apple",
                createdAt = 1_700_000_000_001L
            ),
            sentenceEntity.toFavoriteSentence()
        )
    }

    @Test
    fun `word keys ignore case and surrounding whitespace`() {
        assertEquals("apple", favoriteWordKey("  Apple \t"))
        assertEquals("apple", favoriteWordKey("APPLE"))
    }

    @Test
    fun `sentence keys only trim so inner punctuation and case survive`() {
        assertEquals(
            "Hello, World!",
            favoriteSentenceKey("  Hello, World!  ")
        )
    }

    @Test
    fun `addWord stores the normalized key and optional fields`() = runTest {
        val dao = mockk<FavoriteDao>(relaxed = true)
        val repository = FavoriteRepositoryImpl(
            mockk<AppDatabase> {
                every { favoriteDao } returns dao
            }
        )
        val inserted = slot<FavoriteEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        repository.addWord("  Apple ", "/ˈæpl/", null)

        assertEquals(FavoriteEntityType.WORD, inserted.captured.type)
        assertEquals("apple", inserted.captured.text)
        assertEquals("/ˈæpl/", inserted.captured.phonetic)
        assertNull(inserted.captured.definition)
    }

    @Test
    fun `addSentence keeps the sentence text and drops blank fields`() = runTest {
        val dao = mockk<FavoriteDao>(relaxed = true)
        val repository = FavoriteRepositoryImpl(
            mockk<AppDatabase> {
                every { favoriteDao } returns dao
            }
        )
        val inserted = slot<FavoriteEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        repository.addSentence("  It works.  ", "  ", "**grammar**")

        assertEquals(FavoriteEntityType.SENTENCE, inserted.captured.type)
        assertEquals("It works.", inserted.captured.text)
        assertNull(inserted.captured.translation)
        assertEquals("**grammar**", inserted.captured.grammar)
    }
}
