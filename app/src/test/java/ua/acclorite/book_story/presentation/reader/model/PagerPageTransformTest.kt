/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻页动画变换的数学。
 *
 * 约定（来自 Compose `PagerState.getOffsetDistanceInPages`）：
 *   offsetDistance = page - currentPage - currentPageOffsetFraction
 *   Pager 把页面画在 offsetDistance * pageSize 处（reverseLayout 时取反）
 * 所以「抵消位移」= -offsetDistance * pageSize（reverse 时再取反）。
 *
 * 第一版把 offsetFraction 的符号写成了 `+`，表现为拖到一半时页面跳半页、
 * 两页之间露出背景色。这类错误不会崩溃，只会在真机上「看着不对」。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPageTransformTest {

    private val size = 1000f

    private fun fade(offset: Float, reversed: Boolean = false) =
        pagerPageTransform(ReaderPagedAnimation.FADE, offset, size, reversed)

    @Test
    fun `slide leaves the pager's own translation untouched`() {
        listOf(-1.5f, -0.5f, 0f, 0.5f, 1.5f).forEach { offset ->
            val t = pagerPageTransform(ReaderPagedAnimation.SLIDE, offset, size, reversed = false)
            assertEquals(0f, t.translation, 0.001f)
            assertEquals(1f, t.alpha, 0.001f)
        }
    }

    @Test
    fun `fade cancels the pager translation on both sides of the snap point`() {
        // 当前页（offsetDistance 为 -f）被 Pager 画在 -f*size，抵消后应回到 0
        assertEquals(500f, fade(-0.5f).translation, 0.001f)
        // 下一页（offsetDistance = 1 - f）被画在 (1-f)*size，抵消后同样回到 0
        assertEquals(-500f, fade(0.5f).translation, 0.001f)
        // 上一页
        assertEquals(500f, fade(-0.5f).translation, 0.001f)
    }

    @Test
    fun `fade cancels exactly so both pages end up on top of each other`() {
        // 核心不变量：offsetDistance * size + translation == 0
        listOf(-1.5f, -0.7f, -0.25f, 0f, 0.25f, 0.7f, 1.5f).forEach { offset ->
            val t = fade(offset)
            assertEquals(
                "offset=$offset 时没有完全抵消，两页会分离",
                0f,
                offset * size + t.translation,
                0.001f
            )
        }
    }

    @Test
    fun `fade alpha is 1 at the snap point and 0 a full page away`() {
        assertEquals(1f, fade(0f).alpha, 0.001f)
        assertEquals(0.5f, fade(0.5f).alpha, 0.001f)
        assertEquals(0f, fade(1f).alpha, 0.001f)
        // 快速甩动时 |offset| 可能超过 1，alpha 必须夹住
        assertEquals(0f, fade(1.4f).alpha, 0.001f)
        assertEquals(0f, fade(-1.4f).alpha, 0.001f)
    }

    @Test
    fun `reversed layouts flip the cancellation sign`() {
        // reverseLayout 下 Pager 的位移方向相反，抵消项也必须反号，
        // 否则会叠加成双倍位移（离场页「飞走」）
        assertEquals(-500f, fade(-0.5f, reversed = true).translation, 0.001f)
        assertEquals(500f, fade(0.5f, reversed = true).translation, 0.001f)
    }

    @Test
    fun `draw offset follows the Compose convention with hard-coded values`() {
        // currentPage=0, fraction=0.3（向前拖了 0.3 页）
        // 第 0 页应在 -300，第 1 页应在 +700
        assertEquals(-300f, pagerDrawOffset(0, 0, 0.3f, 1000f, reversed = false), 0.001f)
        assertEquals(700f, pagerDrawOffset(1, 0, 0.3f, 1000f, reversed = false), 0.001f)
        // 上一页在 -1300
        assertEquals(-1300f, pagerDrawOffset(-1, 0, 0.3f, 1000f, reversed = false), 0.001f)
    }

    @Test
    fun `draw offset is continuous across the currentPage flip`() {
        // currentPage 翻转的那一刻：fraction 从 +0.5 跳到 -0.5，
        // 同一页的屏幕位置必须不变，否则翻页会「跳半页」
        val before = pagerDrawOffset(1, 0, 0.5f, 1000f, reversed = false)
        val after = pagerDrawOffset(1, 1, -0.5f, 1000f, reversed = false)
        assertEquals("翻转点不连续，翻页会跳半页", before, after, 0.001f)
        assertEquals(500f, before, 0.001f)
    }

    @Test
    fun `draw offset is mirrored in reversed layouts`() {
        assertEquals(300f, pagerDrawOffset(0, 0, 0.3f, 1000f, reversed = true), 0.001f)
    }

    @Test
    fun `translation scales with page size`() {
        val t = pagerPageTransform(ReaderPagedAnimation.FADE, -0.5f, pageSize = 400f, reversed = false)
        assertEquals(200f, t.translation, 0.001f)
    }
}
