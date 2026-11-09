/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：把取词卡片上的「收藏」压缩成一次纯函数调用。
 *
 * 卡片弹层与收藏页都要从同一个 `LookupState.Success` 生成收藏草稿；如果两处各写一遍，
 * 最容易漏的是「换过候选词条以后，收藏的应该是当前这一条的音标/释义，不是第一条」。
 * 这里按 `selectedIndex` 取命中的词典，与卡片正文用的是同一份。
 */

package ua.acclorite.book_story.ui.reader

import ua.acclorite.book_story.domain.model.favorite.FavoriteWordDraft
import ua.acclorite.book_story.presentation.reader.LookupState

/** 当前展开的词条 → 落库所需的词头、音标、释义摘要。 */
internal fun favoriteWordDraft(state: LookupState.Success): FavoriteWordDraft {
    val hit = state.entries.getOrNull(state.selectedIndex)
    val body = parseLookupBody(
        html = state.html,
        word = state.word,
        hasResources = state.hasResources,
        dictId = hit?.dictId.orEmpty()
    )
    return FavoriteWordDraft(
        word = state.word,
        phonetic = body.phonetic,
        definition = body.favoriteDefinition(word = state.word)
    )
}
