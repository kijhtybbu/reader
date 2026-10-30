/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：长按选区 → 取词的跨行判定（第二阶段 S5 修复）。
 *
 * 选区矩形来自 Compose 的 `SelectionManager.getContentRect()`，底部带手柄内边距；这里钉住
 * 「靠宽度而不是高度判断跨行」的约定（见 ReaderLookupActions.spansMultipleLines）。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.NodeId

class ReaderLookupSelectionTest {

    private fun hit(rect: Rect, lineWidth: Float) = WordHit(
        nodeId = NodeId(1),
        word = "apple",
        rect = rect,
        lineWidth = lineWidth
    )

    @Test
    fun `single line word selection is not treated as multi line`() {
        val word = Rect(left = 100f, top = 200f, right = 180f, bottom = 240f)
        // 选区宽度≈词宽（Compose 会在底部加手柄内边距，宽度不受影响）
        val selection = Rect(left = 100f, top = 200f, right = 180f, bottom = 340f)

        assertFalse(spansMultipleLines(selection, hit(word, lineWidth = 800f)))
    }

    @Test
    fun `multi line selection is rejected`() {
        val word = Rect(left = 300f, top = 240f, right = 380f, bottom = 280f)
        // 跨两行：包围盒宽度≈整行宽
        val selection = Rect(left = 40f, top = 190f, right = 900f, bottom = 330f)

        assertTrue(spansMultipleLines(selection, hit(word, lineWidth = 860f)))
    }

    @Test
    fun `clipped single line selection at the viewport top still counts`() {
        val word = Rect(left = 100f, top = 12f, right = 180f, bottom = 48f)
        // 选区被视口裁掉一截：顶边被抬到 0，但宽度仍≈词宽
        val selection = Rect(left = 100f, top = 0f, right = 180f, bottom = 148f)

        assertFalse(spansMultipleLines(selection, hit(word, lineWidth = 800f)))
    }

    @Test
    fun `line width unknown never reports multi line`() {
        val word = Rect(left = 100f, top = 200f, right = 180f, bottom = 240f)
        val selection = Rect(left = 0f, top = 100f, right = 1904f, bottom = 500f)

        assertFalse(spansMultipleLines(selection, hit(word, lineWidth = 0f)))
    }
}
