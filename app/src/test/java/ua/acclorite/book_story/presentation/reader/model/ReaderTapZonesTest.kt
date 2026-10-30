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
    fun `kindle layout uses a narrow left zone and a wide right zone`() {
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.KINDLE, 0.1f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 0.5f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 0.9f, 0.5f))
    }

    @Test
    fun `kindle layout opens the menu from the top band`() {
        listOf(0.05f, 0.5f, 0.95f).forEach { x ->
            assertEquals(ReaderTapAction.MENU, action(ReaderTapZones.KINDLE, x, 0.05f))
        }
        // 顶部带以下是翻页区，纵向位置不再影响结果
        listOf(0.2f, 0.5f, 0.95f).forEach { y ->
            assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.KINDLE, 0.1f, y))
            assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 0.9f, y))
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
    fun `kindle boundaries are exclusive at the edges`() {
        // 恰好 25% 归入右侧「下一页」
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.KINDLE, 0.24f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 0.25f, 0.5f))
        // 恰好 12% 归入翻页区
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 0.9f, 0.12f))
        // 极端坐标（手指划出边界时会被 coerce 到 0/1）
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.KINDLE, 0f, 0.5f))
        assertEquals(ReaderTapAction.NEXT, action(ReaderTapZones.KINDLE, 1f, 0.5f))
    }

    @Test
    fun `normal direction maps next to +1 and previous to -1`() {
        assertEquals(1, pageDeltaFor(ReaderTapAction.NEXT, reversed = false))
        assertEquals(-1, pageDeltaFor(ReaderTapAction.PREVIOUS, reversed = false))
        // 点右侧宽区应是下一页（页码 +1）
        val right = action(ReaderTapZones.KINDLE, 0.9f, 0.5f)
        assertEquals(1, pageDeltaFor(right, reversed = false))
    }

    @Test
    fun `reversed direction flips the delta, not the zone`() {
        // RIGHT_TO_LEFT：页码向左递增，所以左侧窄区才是「下一页」
        assertEquals(-1, pageDeltaFor(ReaderTapAction.NEXT, reversed = true))
        assertEquals(1, pageDeltaFor(ReaderTapAction.PREVIOUS, reversed = true))

        // 分区本身不变：左侧仍然是 PREVIOUS（位置性），只是它对应的页码增量是 +1
        assertEquals(ReaderTapAction.PREVIOUS, action(ReaderTapZones.KINDLE, 0.1f, 0.5f))
        assertEquals(1, pageDeltaFor(action(ReaderTapZones.KINDLE, 0.1f, 0.5f), reversed = true))
        assertEquals(-1, pageDeltaFor(action(ReaderTapZones.KINDLE, 0.9f, 0.5f), reversed = true))
    }

    @Test
    fun `menu and none never move the page`() {
        listOf(true, false).forEach { reversed ->
            assertEquals(0, pageDeltaFor(ReaderTapAction.MENU, reversed = reversed))
            assertEquals(0, pageDeltaFor(ReaderTapAction.NONE, reversed = reversed))
        }
    }

    @Test
    fun `vertical directions respect the same reversed flag`() {
        // BOTTOM_TO_TOP（vertical + reversed）：下方那个三分之一的 PREVIOUS 应得到 +1
        val bottom = action(ReaderTapZones.TOP_BOTTOM, 0.5f, 0.9f)
        assertEquals(ReaderTapAction.PREVIOUS, bottom)
        assertEquals(1, pageDeltaFor(bottom, reversed = true))
        // TOP_TO_BOTTOM（vertical，未反转）：中间三分之一的 NEXT 就是页码 +1
        val middle = action(ReaderTapZones.TOP_BOTTOM, 0.5f, 0.5f)
        assertEquals(ReaderTapAction.NEXT, middle)
        assertEquals(1, pageDeltaFor(middle, reversed = false))
    }
}
