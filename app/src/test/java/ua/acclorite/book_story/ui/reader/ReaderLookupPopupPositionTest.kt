/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片的定位（第二阶段 S5，设计 §5.2）。
 *
 * 定位规则只有两条，但都很容易在改版时被改坏，所以全部用像素坐标钉住：
 *  1. **方向只看空间**：词上方和下方哪边空间大就朝哪边长，与卡片当前高度无关 ——
 *     这是「点击那一刻就定方向、之后长高不翻面」的前提；
 *  2. **贴词那条边固定**：卡片长高时只有远端在动，词旁边那一侧一像素都不动。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupPopupPositionTest {

    private val window = IntSize(1080, 2000)

    /** 默认安全区 = 整个窗口（无 inset）。 */
    private val screen = IntRect(left = 0, top = 0, right = window.width, bottom = window.height)
    private val popup = IntSize(600, 300)
    // 真实像素量级：12dp / 8dp 在 3x 屏上是 36 / 24 px（用 dp 量级会掩盖边界行为）。
    private val margin = 36
    private val gap = 24

    private fun position(anchor: Rect?, safe: IntRect = screen, size: IntSize = popup) =
        lookupPopupPosition(
            anchor = anchor,
            safeBounds = safe,
            popupSize = size,
            margin = margin,
            gap = gap
        )

    private fun placement(anchor: Rect?, safe: IntRect = screen) =
        lookupPopupPlacement(
            anchor = anchor,
            safeBounds = safe,
            margin = margin,
            gap = gap
        )

    // ---- 方向 ------------------------------------------------------------

    @Test
    fun `a word in the upper half opens the card downwards`() {
        val anchor = Rect(left = 300f, top = 400f, right = 400f, bottom = 430f)

        val placement = placement(anchor)

        assertTrue(placement.below)
        assertEquals(430 + gap, position(anchor).y) // 词底 + 间隙
        // 水平居中于词：350 - 300
        assertEquals(50, position(anchor).x)
    }

    @Test
    fun `a word near the bottom opens the card upwards`() {
        val anchor = Rect(left = 300f, top = 1800f, right = 400f, bottom = 1830f)

        val placement = placement(anchor)

        assertFalse(placement.below)
        assertEquals(1800 - gap - popup.height, position(anchor).y)
    }

    @Test
    fun `the direction does not depend on the card height`() {
        // 下方还剩 660px（放得下 300px 的卡片），但上方剩 1140px 更大：
        // 旧规则（「下面放得下就朝下」）朝下，新规则朝上，且矮卡片时也不改主意。
        val anchor = Rect(left = 300f, top = 1200f, right = 400f, bottom = 1280f)

        assertFalse(placement(anchor).below)
        val shortCard = IntSize(600, 40)
        assertEquals(
            1200 - gap - shortCard.height,
            lookupPopupPosition(anchor, screen, shortCard, margin, gap).y
        )
        // 卡片长高（结果到达）不会让方向反转。
        assertEquals(
            1200 - gap - popup.height,
            lookupPopupPosition(anchor, screen, popup, margin, gap).y
        )
    }

    @Test
    fun `the available space is measured on the chosen side`() {
        // 词在上半屏 → 朝下开，剩的是「词底到安全区底」这一段。
        val upperHalf = Rect(300f, 400f, 400f, 430f)
        val tight = placement(Rect(300f, 1980f, 400f, 2000f))

        val below = placement(upperHalf)
        assertTrue(below.below)
        assertEquals(2000 - margin - (430 + gap), below.availableSpace)
        assertFalse(tight.below)
        assertEquals(1980 - gap - margin, tight.availableSpace)
    }

    @Test
    fun `the chosen side is always the roomier one`() {
        listOf(1200, 2000, 3040).forEach { height ->
            val safe = IntRect(0, 0, window.width, height)
            (0..height - 60 step 40).forEach { top ->
                val anchor = Rect(300f, top.toFloat(), 400f, (top + 50).toFloat())
                val spaceBelow = height - margin - (top + 50 + gap)
                val spaceAbove = top - gap - margin
                assertEquals(
                    "anchor=$top/$height",
                    maxOf(spaceBelow, spaceAbove),
                    placement(anchor, safe).availableSpace
                )
            }
        }
    }

    @Test
    fun `the height cap is the design cap but never more than the free space`() {
        // 竖屏：安全区够高，45% 的设计上限先起作用。
        val portrait = IntRect(0, 0, window.width, 3040)
        val portraitAnchor = Rect(300f, 2000f, 400f, 2060f)
        assertEquals(
            (3040 * PopupMaxHeightFraction).toInt(),
            lookupPopupMaxHeightPx(portrait, placement(portraitAnchor, portrait))
        )

        // 横屏：安全区只有 1080px，45%（486px）比词上方剩的空间还大，上限被该侧空间收紧，
        // 卡片变矮而不是「长到一半被夹回来」。
        val landscape = IntRect(0, 0, window.width, 1080)
        val landscapeAnchor = Rect(300f, 520f, 400f, 570f)
        val landscapePlacement = placement(landscapeAnchor, landscape)
        val maxHeight = lookupPopupMaxHeightPx(landscape, landscapePlacement)
        assertTrue(maxHeight < (1080 * PopupMaxHeightFraction).toInt())
        assertEquals(landscapePlacement.availableSpace, maxHeight)
        // 贴词边（词上方卡片的底边）仍然落在词的紧上方。
        val offset = lookupPopupPosition(
            landscapeAnchor, landscape, IntSize(600, maxHeight), margin, gap
        )
        assertEquals(520 - gap, offset.y + maxHeight)
    }

    // ---- 贴词边固定 ------------------------------------------------------

    @Test
    fun `growing a card below the word keeps its top edge`() {
        val anchor = Rect(300f, 400f, 400f, 430f)

        val short = position(anchor, size = IntSize(600, 120))
        val tall = position(anchor, size = IntSize(600, 900))

        assertEquals(short.y, tall.y)
        assertEquals(430 + gap, tall.y)
    }

    @Test
    fun `growing a card above the word keeps its bottom edge`() {
        val anchor = Rect(300f, 1800f, 400f, 1830f)

        val short = position(anchor, size = IntSize(600, 120))
        val tall = position(anchor, size = IntSize(600, 900))

        // 底边 = 词顶 − 间隙，两个高度下都一样。
        assertEquals(1800 - gap, short.y + 120)
        assertEquals(1800 - gap, tall.y + 900)
    }

    // ---- 横向与边距 ------------------------------------------------------

    @Test
    fun `the card is clamped inside the screen horizontally`() {
        val nearLeft = position(Rect(0f, 400f, 20f, 430f))
        val nearRight = position(Rect(1060f, 400f, 1080f, 430f))

        assertEquals(margin, nearLeft.x)
        assertEquals(window.width - popup.width - margin, nearRight.x)
    }

    @Test
    fun `an unknown anchor centers the card`() {
        val offset = position(null)

        assertEquals((window.width - popup.width) / 2, offset.x)
        assertEquals((window.height - popup.height) / 2, offset.y)
    }

    @Test
    fun `system bar and cutout insets shrink the clamp range`() {
        // 横屏：左边有 100px 挖孔，顶部有 120px 状态栏。
        val safe = IntRect(left = 100, top = 120, right = window.width, bottom = window.height)

        assertEquals(100 + margin, position(Rect(0f, 400f, 20f, 430f), safe = safe).x)
        assertEquals(
            window.width - popup.width - margin,
            position(Rect(1060f, 400f, 1080f, 430f), safe = safe).x
        )
    }

    @Test
    fun `an unknown anchor centers inside the safe area`() {
        val safe = IntRect(left = 100, top = 120, right = window.width, bottom = 2000)
        val offset = position(null, safe = safe)

        assertEquals(100 + (window.width - 100 - popup.width) / 2, offset.x)
        assertEquals(120 + (2000 - 120 - popup.height) / 2, offset.y)
    }

    @Test
    fun `a card taller than the space still starts inside the safe area`() {
        // 上面只有 300px 的空间，却给了 1800px 高的卡片：方向和贴词边都保不住，只能夹进安全区。
        val safe = IntRect(left = 0, top = 120, right = window.width, bottom = window.height)

        val offset = position(
            anchor = Rect(300f, 400f, 400f, 430f),
            safe = safe,
            size = IntSize(600, 1800)
        )

        // 顶边钉在词底不成立（会顶出屏幕）；夹回来的结果是底面贴安全区底边。
        assertEquals(safe.bottom - 1800 - margin, offset.y)
        assertTrue(offset.y >= safe.top)
    }

    @Test
    fun `an empty anchor rect is not treated as a missing one`() {
        // 选区塌成一条线（落在行首）时不该退化成「居中」。
        val anchor = Rect(300f, 1200f, 300f, 1200f)

        // 方向仍按空间算（词在下方 → 朝上开），横向仍是「居中于词」而不是「居中于屏幕」。
        assertFalse(placement(anchor).below)
        assertEquals(IntOffset(margin, 1200 - gap - popup.height), position(anchor))
    }
}
