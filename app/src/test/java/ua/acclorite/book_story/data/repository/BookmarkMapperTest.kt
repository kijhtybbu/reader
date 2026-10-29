/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签实体与领域模型的映射。
 *
 * 看起来是样板代码，但漏字段是这类映射最常见的 bug —— 而漏掉 nodeIntraOffset
 * 会让书签跳到段落开头而不是当初那一行，且不会报任何错。
 */

package ua.acclorite.book_story.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.domain.model.bookmark.Bookmark

class BookmarkMapperTest {

    private val bookmark = Bookmark(
        id = 7,
        bookId = 3,
        nodeId = 128,
        nodeIntraOffset = 42,
        chapterTitle = "第三章",
        snippet = "他合上书，窗外的雨还没停。",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `round trip preserves every field`() {
        assertEquals(bookmark, bookmark.toEntity().toDomain())
    }

    @Test
    fun `position survives the round trip`() {
        // 单独断言定位字段：丢了它书签会跳到段落开头
        val entity = bookmark.toEntity()
        assertEquals(128, entity.nodeId)
        assertEquals(42, entity.nodeIntraOffset)
    }

    @Test
    fun `new bookmark keeps id zero so room can auto generate`() {
        assertEquals(0, bookmark.copy(id = 0).toEntity().id)
    }
}
