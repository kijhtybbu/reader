/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：阅读页书签按钮的三种含义。
 */

package ua.acclorite.book_story.ui.reader

import ua.acclorite.book_story.domain.model.bookmark.Bookmark

/**
 * fork 新增：书签按钮当前会做什么。图标可以只有两种（实心/空心），但动作有三种：
 * 这一段没有书签 → 新增；书签正好落在当前行 → 删除；书签在这一段但不在这行 → 移动。
 * 第三种必须有自己的无障碍描述，否则图标在「会移动」的时候念成「取消收藏」。
 */
enum class BookmarkButtonState {
    NONE,
    HERE,
    ELSEWHERE
}

/**
 * 现有书签 [bookmark]（来自 [ua.acclorite.book_story.domain.model.bookmark.bookmarkForNode]）与当前
 * 行内偏移 [nodeIntraOffset] 组合出的按钮状态。
 *
 * 段落里有没有书签只看 nodeId，是"删除"还是"移动"才看 nodeIntraOffset ——
 * 改字号/行高/左右留白后同一行的段内起点会变，所以即使只是换了字号，这里也可能判成 ELSEWHERE。
 */
internal fun bookmarkButtonState(
    bookmark: Bookmark?,
    nodeIntraOffset: Int
): BookmarkButtonState = when {
    bookmark == null -> BookmarkButtonState.NONE
    bookmark.nodeIntraOffset == nodeIntraOffset -> BookmarkButtonState.HERE
    else -> BookmarkButtonState.ELSEWHERE
}
