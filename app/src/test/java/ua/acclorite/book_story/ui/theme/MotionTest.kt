/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：动效 token 的回归测试。
 *
 * 存在的理由：Phase 1 第一版把 helper 的默认缓动改成了自定义的 M3 standard 曲线，
 * 却在文件头和提交信息里声称「零视觉变化」。当时没有任何测试能发现这一点 ——
 * 38 处动画全部静默改了手感。这个测试把那句声明变成可执行断言。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MotionTest {

    private val helpers = listOf<Pair<Int, () -> Int>>(
        Motion.Instant to { Motion.instant<Float>().durationMillis },
        Motion.Fast to { Motion.fast<Float>().durationMillis },
        Motion.Short to { Motion.short<Float>().durationMillis },
        Motion.Base to { Motion.base<Float>().durationMillis },
        Motion.Medium to { Motion.medium<Float>().durationMillis },
        Motion.Long to { Motion.long<Float>().durationMillis },
        Motion.Slower to { Motion.slower<Float>().durationMillis },
        Motion.Slowest to { Motion.slowest<Float>().durationMillis }
    )

    /**
     * 每个 helper 的默认缓动必须是 Compose `tween()` 的默认值。
     * 迁移前所有调用点都是裸 `tween(N)`，改变默认缓动就等于改动全部动画。
     */
    @Test
    fun `every helper defaults to the Compose tween easing`() {
        listOf(
            Motion.instant<Float>(),
            Motion.fast<Float>(),
            Motion.short(),
            Motion.base(),
            Motion.medium(),
            Motion.long(),
            Motion.slower(),
            Motion.slowest()
        ).forEach { spec ->
            assertSame(
                "默认缓动必须与 Compose tween 的默认值保持一致",
                FastOutSlowInEasing,
                spec.easing
            )
        }
    }

    @Test
    fun `helpers keep their documented durations`() {
        helpers.forEach { (expected, actual) ->
            assertEquals("token 时长被改动", expected, actual())
        }
    }

    /** 8 档时长必须互不相同，否则 token 阶梯就失去意义 */
    @Test
    fun `duration scale is strictly increasing`() {
        val durations = helpers.map { it.first }
        assertEquals("时长阶梯有重复值", durations.size, durations.toSet().size)
        assertEquals("时长阶梯必须递增", durations.sorted(), durations)
    }

    @Test
    fun `explicit easing is honoured`() {
        assertSame(Motion.Balanced, Motion.long<Float>(Motion.Balanced).easing)
        assertSame(Motion.Emphasized, Motion.short<Float>(Motion.Emphasized).easing)
    }
}
