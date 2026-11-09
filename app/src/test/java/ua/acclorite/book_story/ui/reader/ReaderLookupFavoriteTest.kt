/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片 → 收藏草稿的回归。
 *
 * 收藏的是**当前展开的那条命中**：用户在多词典命中的 chip 里切到第 2 条后点五角星，
 * 收藏页显示的必须是第 2 条的音标/释义，而不是第一条。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.data.dictionary.DictionaryHit
import ua.acclorite.book_story.presentation.reader.LookupState

class ReaderLookupFavoriteTest {

    private fun hit(dictId: String, dictName: String) = DictionaryHit(
        dictId = dictId,
        dictName = dictName,
        word = "apple",
        offset = 0,
        size = 1,
        preview = "",
        rank = DictionaryHit.RANK_EXACT
    )

    @Test
    fun `draft takes the word phonetic and definition from the entry`() {
        val html = "<b>apple</b>" +
            "<br><span style=\"color:#888\">/ˈæpl/</span>" +
            "<br>n. 苹果, 家伙" +
            "<br><!--def-->n. fruit with red or yellow or green skin"

        val draft = favoriteWordDraft(
            LookupState.Success(
                word = "apple",
                anchor = null,
                entries = listOf(hit("ecdict", "ECDICT")),
                selectedIndex = 0,
                html = html
            )
        )

        assertEquals("apple", draft.word)
        assertEquals("/ˈæpl/", draft.phonetic)
        assertEquals("n. 苹果, 家伙", draft.definition)
    }

    @Test
    fun `draft follows the selected entry after switching dictionaries`() {
        val draft = favoriteWordDraft(
            LookupState.Success(
                word = "apple",
                anchor = null,
                entries = listOf(hit("ecdict", "ECDICT"), hit("oald", "OALD9")),
                selectedIndex = 1,
                html = "<div><b>apple</b></div><div>n. the round fruit of a tree</div>",
                hasResources = true
            )
        )

        assertEquals("apple", draft.word)
        // 第 2 条没有音标，不能把第 1 条的塞进来。
        assertEquals(null, draft.phonetic)
        assertEquals("n. the round fruit of a tree", draft.definition)
    }
}
