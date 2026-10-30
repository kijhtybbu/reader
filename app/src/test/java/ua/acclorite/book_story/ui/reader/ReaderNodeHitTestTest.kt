/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：双击翻译的纵向命中。
 *
 * 之前每段自己挂点击检测器，所以不需要命中测试；现在只有一个检测器，
 * 必须把 y 坐标换算成段落节点。算错的后果是「双击这一段，翻译了上一段」。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.Node
import ua.acclorite.book_story.domain.model.book.NodeContent
import ua.acclorite.book_story.domain.model.book.NodeId
import ua.acclorite.book_story.domain.model.book.NodePosition
import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.reader.paged.ReaderPage
import ua.acclorite.book_story.ui.reader.paged.ReaderPageItem

class ReaderNodeHitTestTest {

    private fun paragraph(id: Int, text: String): ReaderNode.Paragraph = ReaderNode.Paragraph(
        Node(NodeId(id), NodePosition(text.length, 0), NodeContent.Paragraph(text, emptyList())),
        AnnotatedString(text)
    )

    private fun item(node: ReaderNode, height: Int, spaceBefore: Int) =
        ReaderPageItem(
            node = node,
            startOffset = 0,
            endOffset = 1,
            height = height,
            spaceBefore = spaceBefore
        )

    // 布局：gap 8 → 段A 100 → gap 8 → 段B 100
    private val page = ReaderPage(
        listOf(
            item(paragraph(0, "A"), height = 100, spaceBefore = 0),
            item(paragraph(1, "B"), height = 100, spaceBefore = 8)
        )
    )

    @Test
    fun `hit inside the first paragraph`() {
        assertEquals(0, (readerNodeAtY(page, 50f) as ReaderNode.Paragraph).node.id.id)
    }

    @Test
    fun `hit inside the paragraph gap belongs to the following paragraph`() {
        // y=104 落在第二段的 spaceBefore 里，应归给第二段而不是第一段
        assertEquals(1, (readerNodeAtY(page, 104f) as ReaderNode.Paragraph).node.id.id)
    }

    @Test
    fun `hit inside the second paragraph`() {
        assertEquals(1, (readerNodeAtY(page, 150f) as ReaderNode.Paragraph).node.id.id)
    }

    @Test
    fun `hit below the last paragraph hits nothing`() {
        // 页面下方空白处双击不应该翻译最后一段
        assertNull(readerNodeAtY(page, 999f))
    }

    @Test
    fun `negative coordinates hit nothing`() {
        assertNull(readerNodeAtY(page, -1f))
    }

    // ---- S5 取词：页项命中 ----------------------------------------------------

    @Test
    fun `item hit reports the text top and the local offset`() {
        val hit = requireNotNull(readerPageItemAtY(page, 150f))

        assertEquals(1, (hit.item.node as ReaderNode.Paragraph).node.id.id)
        // 第二段：spaceBefore 8 + 第一段 100 = 文本块顶部在 108
        assertEquals(108f, hit.topInPage, 0.01f)
        assertEquals(42f, hit.localY, 0.01f)
    }

    @Test
    fun `a tap inside the paragraph gap belongs to the next item but with a negative local offset`() {
        val hit = requireNotNull(readerPageItemAtY(page, 104f))

        assertEquals(1, (hit.item.node as ReaderNode.Paragraph).node.id.id)
        // localY < 0 表示点在段前空隙里：取词要当成空白（那里没有字）
        assertEquals(-4f, hit.localY, 0.01f)
    }

    @Test
    fun `item hit below the last item hits nothing`() {
        assertNull(readerPageItemAtY(page, 999f))
        assertNull(readerPageItemAtY(page, -1f))
    }
}
