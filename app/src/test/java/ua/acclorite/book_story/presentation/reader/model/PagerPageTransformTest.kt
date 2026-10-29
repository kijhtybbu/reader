/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻页动画变换的数学。
 *
 * 为什么值得单测：这段数学错了不会崩，只会「翻页看起来怪」（页面跳、重影、
 * 或两页一起滑），而它又很难靠肉眼在小屏幕上分辨。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerPageTransformTest {

    private val width = 1000f

    @Test
    fun `slide leaves the pager's own translation untouched`() {
        listOf(-1f, -0.5f, 0f, 0.5f, 1f).forEach { offset ->
            val t = pagerPageTransform(ReaderPagedAnimation.SLIDE, offset, width)
            assertEquals(0f, t.translationX, 0.001f)
            assertEquals(1f, t.alpha, 0.001f)
        }
    }

    @Test
    fun `cover keeps the outgoing page still and lets the next slide over it`() {
        assertEquals(500f, pagerPageTransform(ReaderPagedAnimation.COVER, -0.5f, width).translationX, 0.001f)
        assertEquals(1000f, pagerPageTransform(ReaderPagedAnimation.COVER, -1f, width).translationX, 0.001f)
        assertEquals(0f, pagerPageTransform(ReaderPagedAnimation.COVER, 0.5f, width).translationX, 0.001f)
        assertEquals(1f, pagerPageTransform(ReaderPagedAnimation.COVER, -0.5f, width).alpha, 0.001f)
    }

    @Test
    fun `fade cancels translation on both pages and cross fades`() {
        val left = pagerPageTransform(ReaderPagedAnimation.FADE, -0.5f, width)
        val right = pagerPageTransform(ReaderPagedAnimation.FADE, 0.5f, width)

        assertEquals(500f, left.translationX, 0.001f)
        assertEquals(-500f, right.translationX, 0.001f)
        assertEquals(0.5f, left.alpha, 0.001f)
        assertEquals(0.5f, right.alpha, 0.001f)
    }

    @Test
    fun `fade alpha is fully opaque at rest and never negative`() {
        assertEquals(1f, pagerPageTransform(ReaderPagedAnimation.FADE, 0f, width).alpha, 0.001f)
        assertEquals(0f, pagerPageTransform(ReaderPagedAnimation.FADE, 1.4f, width).alpha, 0.001f)
        assertEquals(0f, pagerPageTransform(ReaderPagedAnimation.FADE, -1.4f, width).alpha, 0.001f)
    }

    @Test
    fun `transform scales with page width`() {
        assertEquals(250f, pagerPageTransform(ReaderPagedAnimation.COVER, -0.5f, 500f).translationX, 0.001f)
    }
}
