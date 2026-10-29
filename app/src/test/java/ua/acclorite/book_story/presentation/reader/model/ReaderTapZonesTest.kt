/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：点击分区命中逻辑。
 *
 * 这类错误的表现是「点左边翻到下一页」，自测时很容易以为是手滑。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapZonesTest {

    private fun action(zones: ReaderTapZones, x: Float, y: Float) = tapActionFor(zones, x, y)

    @Test
    fun `sides layout splits horizontally`() {
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.SIDES, 0.1f, 0.5f))
        assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.SIDES, 0.5f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.SIDES, 0.9f, 0.5f))
    }

    @Test
    fun `sides layout ignores the vertical position`() {
        // 同一个横向位置，上中下都应得到相同结果
        listOf(0.05f, 0.5f, 0.95f).forEach { y ->
            assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.SIDES, 0.1f, y))
            assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.SIDES, 0.9f, y))
        }
    }

    @Test
    fun `top bottom layout splits vertically`() {
        assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.TOP_BOTTOM, 0.5f, 0.1f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.TOP_BOTTOM, 0.5f, 0.5f))
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.TOP_BOTTOM, 0.5f, 0.9f))
    }

    @Test
    fun `top bottom layout ignores the horizontal position`() {
        listOf(0.05f, 0.5f, 0.95f).forEach { x ->
            assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.TOP_BOTTOM, x, 0.1f))
            assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.TOP_BOTTOM, x, 0.5f))
            assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.TOP_BOTTOM, x, 0.9f))
        }
    }

    @Test
    fun `centre only responds inside the middle ninth`() {
        assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.CENTER_ONLY, 0.5f, 0.5f))
        // 横向中间但纵向靠边 → 不响应
        assertEquals(ReaderTapAction.NONE, action(ReaderTapZones.CENTER_ONLY, 0.5f, 0.1f))
        // 纵向中间但横向靠边 → 不响应
        assertEquals(ReaderTapAction.NONE, action(ReaderTapZones.CENTER_ONLY, 0.1f, 0.5f))
    }

    @Test
    fun `zone boundaries are exclusive at the edges`() {
        val third = 1f / 3f
        // 恰好在分界线上应落到「中间」区
        assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.SIDES, third, 0.5f))
        assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.SIDES, 1f - third, 0.5f))
        // 极端坐标（手指划出边界时会被 coerce 到 0/1）
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.SIDES, 0f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.SIDES, 1f, 0.5f))
    }
}
