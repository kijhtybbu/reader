/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupPopupScrollTest {

    private val threshold = 24
    private val cooldown = 300L
    private val tracker = ScrollDirectionTracker(threshold, cooldown)

    @Test
    fun `initial state does not toggle visibility when delta is zero`() {
        var visible = true
        var toggled = false

        tracker.onScroll(delta = 0, current = 0, isVisible = visible) {
            visible = it
            toggled = true
        }

        assertTrue(visible)
        assertFalse(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `scrolling down under threshold does not hide chips`() {
        var visible = true
        var toggled = false

        // 步进 10px，未达到 24px 阈值
        tracker.onScroll(delta = 10, current = 10, isVisible = visible) {
            visible = it
            toggled = true
        }

        assertTrue(visible)
        assertFalse(toggled)
        assertEquals(10, tracker.accumulatedDelta)

        // 再走 10px，累计 20px，仍未达到阈值
        tracker.onScroll(delta = 10, current = 20, isVisible = visible) {
            visible = it
            toggled = true
        }

        assertTrue(visible)
        assertFalse(toggled)
        assertEquals(20, tracker.accumulatedDelta)
    }

    @Test
    fun `scrolling down exactly at threshold boundary hides chips`() {
        var visible = true
        var toggled = false

        tracker.onScroll(delta = threshold, current = threshold, isVisible = visible) {
            visible = it
            toggled = true
        }

        assertFalse(visible)
        assertTrue(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `scrolling down exceeding threshold hides chips and starts collapse cooldown`() {
        var visible = true
        var toggled = false
        val now = 1000L

        tracker.onScroll(delta = 15, current = 15, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }
        assertFalse(toggled)

        tracker.onScroll(delta = 15, current = 30, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }

        assertFalse(visible)
        assertTrue(toggled)
        assertEquals(0, tracker.accumulatedDelta)
        assertEquals(now + cooldown, tracker.collapseCooldownUntilMillis)
    }

    @Test
    fun `layout clamp induced negative delta and fake top during collapse cooldown is suppressed`() {
        var visible = true
        var toggled = false
        val now = 1000L

        // 用户向下滚动 30px，触发折叠
        tracker.onScroll(delta = 30, current = 30, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }
        assertFalse(visible)
        assertTrue(toggled)

        // 动画期间（100ms 后），视口高度扩展导致 ScrollState/WebView 产生回夹触顶 (current = 0)
        toggled = false
        tracker.onScroll(delta = -45, current = 0, isVisible = visible, nowMillis = now + 100L) {
            visible = it
            toggled = true
        }

        // 冷却期内假触顶被拦截，chips 保持折叠，不发生闪烁
        assertFalse(visible)
        assertFalse(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `continued scrolling down keeps chips hidden and does not accumulate delta`() {
        var visible = false
        var toggled = false

        tracker.onScroll(delta = 50, current = 80, isVisible = visible) {
            visible = it
            toggled = true
        }

        assertFalse(visible)
        assertFalse(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `scrolling upward in the middle of content keeps chips hidden`() {
        var visible = false
        var toggled = false
        val now = 2000L // 远离冷却期

        // 在中段（current = 65 > 0）向下滑动返回，即使大幅滑动，只要未触顶（current > 0）就不展开
        tracker.onScroll(delta = -50, current = 65, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }

        assertFalse(visible)
        assertFalse(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `scrolling all the way to the top reveals chips and starts expand cooldown`() {
        var visible = false
        var toggled = false
        val now = 2000L // 远离冷却期

        // 滚动到头（current = 0 <= 0）时立即触发展开
        tracker.onScroll(delta = -10, current = 0, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }

        assertTrue(visible)
        assertTrue(toggled)
        assertEquals(0, tracker.accumulatedDelta)
        assertEquals(now + cooldown, tracker.expandCooldownUntilMillis)
    }

    @Test
    fun `expansion cooldown suppresses subsequent positive deltas preventing bounce`() {
        var visible = false
        var toggled = false
        val now = 2000L

        // 用户拉到顶展开
        tracker.onScroll(delta = -10, current = 0, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }
        assertTrue(visible)
        assertTrue(toggled)
        assertEquals(now + cooldown, tracker.expandCooldownUntilMillis)

        // 展开动画期间（100ms 后），触顶回弹（spring-back）或视口缩减产生正向增量
        toggled = false
        tracker.onScroll(delta = 50, current = 10, isVisible = visible, nowMillis = now + 100L) {
            visible = it
            toggled = true
        }

        // 展开冷却期内正向位移被拦截，chips 保持展开，不发生弹跳缩回
        assertTrue(visible)
        assertFalse(toggled)
        assertEquals(0, tracker.accumulatedDelta)
    }

    @Test
    fun `negative current from overscroll reveals chips outside collapse cooldown`() {
        var visible = false
        var toggled = false
        val now = 2000L // 远离冷却期

        // WebView 或弹性滚动产生负坐标
        tracker.onScroll(delta = -10, current = -5, isVisible = visible, nowMillis = now) {
            visible = it
            toggled = true
        }

        assertTrue(visible)
        assertTrue(toggled)
        assertEquals(0, tracker.accumulatedDelta)
        assertEquals(now + cooldown, tracker.expandCooldownUntilMillis)
    }

    @Test
    fun `reset clears accumulated delta and cooldowns`() {
        var visible = true
        tracker.onScroll(delta = 30, current = 30, isVisible = visible, nowMillis = 1000L) {}
        assertEquals(1300L, tracker.collapseCooldownUntilMillis)

        tracker.reset()
        assertEquals(0, tracker.accumulatedDelta)
        assertEquals(0L, tracker.collapseCooldownUntilMillis)
        assertEquals(0L, tracker.expandCooldownUntilMillis)
    }
}
