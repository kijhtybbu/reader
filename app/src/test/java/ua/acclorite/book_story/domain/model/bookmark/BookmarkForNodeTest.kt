/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签查找谓词。
 *
 * 图标指示与点按去重都用它；这个谓词一旦退回 (nodeId, nodeIntraOffset) 精确匹配，
 * 改字号后已收藏的行会显示成未收藏，再点一次就多出一条书签。
 */

package ua.acclorite.book_story.domain.model.bookmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkForNodeTest {

    private fun bookmark(nodeId: Int, nodeIntraOffset: Int = 0) = Bookmark(
        id = nodeId * 100 + nodeIntraOffset,
        bookId = 3,
        nodeId = nodeId,
        nodeIntraOffset = nodeIntraOffset,
        chapterTitle = "第三章",
        snippet = "他合上书，窗外的雨还没停。",
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `finds bookmark of the paragraph`() {
        val bookmarks = listOf(bookmark(nodeId = 12, nodeIntraOffset = 40))

        assertEquals(bookmark(nodeId = 12, nodeIntraOffset = 40), bookmarkForNode(bookmarks, 12))
    }

    @Test
    fun `returns null when paragraph is not bookmarked`() {
        val bookmarks = listOf(bookmark(nodeId = 12), bookmark(nodeId = 18))

        assertNull(bookmarkForNode(bookmarks, 13))
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(bookmarkForNode(emptyList<Bookmark>(), 12))
    }

    @Test
    fun `matches by nodeId even when the stored offset differs after a typography change`() {
        // 改字号后这一行的段内起点从 120 变成了 96，仍然必须是同一个书签
        val bookmarks = listOf(bookmark(nodeId = 55, nodeIntraOffset = 120))

        assertEquals(bookmarks.first(), bookmarkForNode(bookmarks, 55))
    }

    @Test
    fun `picks the matching paragraph among many`() {
        val bookmarks = listOf(
            bookmark(nodeId = 1, nodeIntraOffset = 0),
            bookmark(nodeId = 7, nodeIntraOffset = 33),
            bookmark(nodeId = 9, nodeIntraOffset = 5)
        )

        assertEquals(7, bookmarkForNode(bookmarks, 7)?.nodeId)
        assertEquals(9, bookmarkForNode(bookmarks, 9)?.nodeId)
        assertEquals(5, bookmarkForNode(bookmarks, 9)?.nodeIntraOffset)
    }

    @Test
    fun `legacy duplicates in one paragraph resolve to the first one`() {
        // 旧版本的精确匹配去重可能给同一段落留下多条，这里保证结果确定且不崩
        val bookmarks = listOf(
            bookmark(nodeId = 4, nodeIntraOffset = 10),
            bookmark(nodeId = 4, nodeIntraOffset = 90)
        )

        assertEquals(10, bookmarkForNode(bookmarks, 4)?.nodeIntraOffset)
    }
}
