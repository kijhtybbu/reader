/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签按钮状态。
 *
 * 图标只有实心/空心两种，动作却有三种；状态判错时按钮会念「取消收藏」，
 * 点下去却把书签挪走了 —— 对读屏用户来说这是无法纠正的错误提示。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.domain.model.bookmark.Bookmark

class BookmarkButtonStateTest {

    private val bookmark = Bookmark(
        id = 11,
        bookId = 3,
        nodeId = 128,
        nodeIntraOffset = 42,
        chapterTitle = "第三章",
        snippet = "他合上书，窗外的雨还没停。",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `no bookmark means the button adds one`() {
        assertEquals(BookmarkButtonState.NONE, bookmarkButtonState(null, nodeIntraOffset = 42))
    }

    @Test
    fun `bookmark on the current line means the button removes it`() {
        assertEquals(
            BookmarkButtonState.HERE,
            bookmarkButtonState(bookmark, nodeIntraOffset = 42)
        )
    }

    @Test
    fun `bookmark on another line of the same paragraph means the button moves it`() {
        // 改字号/行高后同一行的段内起点会变，这里就是那种情况
        assertEquals(
            BookmarkButtonState.ELSEWHERE,
            bookmarkButtonState(bookmark, nodeIntraOffset = 60)
        )
    }
}
