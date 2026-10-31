/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片的定位（第二阶段 S5，设计 §5.2）。
 *
 * 卡片贴不到词上、或者一半在屏幕外，是这类浮层最容易出的问题，而且只在特定字号/横屏下出现。
 * 这里把定位抽成纯函数，用像素坐标直接钉住四种情况：贴词下方、翻到上方、贴边夹住、居中；
 * 外加「夹在安全区内」：系统栏/刘海/挖孔的高度都是非 0 的，卡片不能钻到它们下面。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupPopupPositionTest {

    private val window = IntSize(1080, 2000)

    /** 默认安全区 = 整个窗口（无 inset），与 S5 之前的行为一致。 */
    private val screen = IntRect(left = 0, top = 0, right = window.width, bottom = window.height)
    private val popup = IntSize(600, 300)
    private val margin = 12
    private val gap = 8

    private fun position(anchor: Rect?, safe: IntRect = screen, size: IntSize = popup) =
        lookupPopupPosition(
            anchor = anchor,
            safeBounds = safe,
            popupSize = size,
            margin = margin,
            gap = gap
        )

    @Test
    fun `the card sits below the word when there is room`() {
        val anchor = Rect(left = 300f, top = 400f, right = 400f, bottom = 430f)

        val offset = position(anchor)

        assertEquals(438, offset.y) // bottom + gap
        // 水平居中于词：350 - 300
        assertEquals(50, offset.x)
    }

    @Test
    fun `the card flips above the word when the bottom would overflow`() {
        val anchor = Rect(left = 300f, top = 1800f, right = 400f, bottom = 1830f)

        val offset = position(anchor)

        assertEquals(1800 - gap - popup.height, offset.y)
        assertTrue(offset.y + popup.height <= screen.bottom - margin)
    }

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
    fun `a popup taller than the screen still starts inside it`() {
        val huge = IntSize(600, 4000)

        val offset = position(Rect(300f, 400f, 400f, 430f), size = huge)

        assertEquals(margin, offset.y)
    }

    @Test
    fun `system bar and cutout insets shrink the clamp range`() {
        // 横屏：左边有 100px 挖孔，顶部有 120px 状态栏。
        val safe = IntRect(left = 100, top = 120, right = window.width, bottom = window.height)

        val nearLeft = position(Rect(0f, 400f, 20f, 430f), safe = safe)
        assertEquals(100 + margin, nearLeft.x)

        val nearRight = position(Rect(1060f, 400f, 1080f, 430f), safe = safe)
        assertEquals(window.width - popup.width - margin, nearRight.x)
    }

    @Test
    fun `an unknown anchor centers inside the safe area`() {
        val safe = IntRect(left = 100, top = 120, right = window.width, bottom = 2000)
        val offset = position(null, safe = safe)

        assertEquals(100 + (window.width - 100 - popup.width) / 2, offset.x)
        assertEquals(120 + (2000 - 120 - popup.height) / 2, offset.y)
    }

    @Test
    fun `a card taller than the safe area is clamped inside it`() {
        // 卡片 1800 高、词在顶部：下方（1550）比上方（260）宽，按规则贴在下方，
        // 但整张卡放不下 —— 必须夹进安全区，不能把状态栏盖住。
        val safe = IntRect(left = 0, top = 120, right = window.width, bottom = window.height)
        val tall = IntSize(600, 1800)

        val offset = position(
            anchor = Rect(300f, 400f, 400f, 430f),
            safe = safe,
            size = tall
        )

        assertEquals(safe.bottom - margin - tall.height, offset.y)
        assertTrue(offset.y >= safe.top + margin)
    }

    @Test
    fun `the direction is decided before the content is measured`() {
        // Loading 卡片只有 300 高，下方够放；但完整词条可能长到 900。按「当前高度」判方向
        // 会让卡片先在下方闪一下再翻上去 —— 所以方向要用最大高度（reservedHeight）来定。
        val anchor = Rect(left = 300f, top = 1100f, right = 400f, bottom = 1130f)

        val below = lookupPopupPosition(anchor, screen, popup, margin, gap, placementHeight = 300)
        val reserved = lookupPopupPosition(anchor, screen, popup, margin, gap, placementHeight = 900)

        // 只有当前高度够放时贴在下方
        assertEquals(1138, below.y)
        // 按最高卡片算，下方放不下 → 一开始就翻到上方（卡片只占 300 高，底边贴词头）
        assertEquals(1100 - gap - popup.height, reserved.y)
        assertTrue(reserved.y + popup.height <= 1100 - gap)
    }

    @Test
    fun `when neither side fits the taller card the larger side wins`() {
        // 词在窗口中间偏下：上方 980、下方 950，都放不下 1800 高的卡片 —— 选上方。
        val anchor = Rect(left = 300f, top = 1000f, right = 400f, bottom = 1030f)
        val tall = IntSize(600, 900)

        val offset = lookupPopupPosition(
            anchor = anchor,
            safeBounds = screen,
            popupSize = tall,
            margin = margin,
            gap = gap,
            placementHeight = 1800
        )

        // 上方空间更大 → 翻到上方，并以底边对齐词头
        assertEquals(1000 - gap - tall.height, offset.y)
        assertTrue(offset.y >= margin)
    }

    @Test
    fun `a card flipped above grows upwards without changing the anchor edge`() {
        // 同一方向下卡片变高，底边位置保持不变（内容向上长），不会横向/纵向跳来跳去。
        val anchor = Rect(left = 300f, top = 1700f, right = 400f, bottom = 1730f)
        val short = IntSize(600, 200)
        val grown = IntSize(600, 600)

        val a = lookupPopupPosition(anchor, screen, short, margin, gap, placementHeight = 900)
        val b = lookupPopupPosition(anchor, screen, grown, margin, gap, placementHeight = 900)

        assertEquals(a.y + short.height, b.y + grown.height)
    }
}
