/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：动效 token。
 *
 * 背景：上游把 durationMillis 字面量散落在 38 处（0/100/150/200/250/300/350/400），
 * 想调整整体节奏就得全局搜索替换，而且无法回答「本 App 的动效有哪几档」。
 *
 * 本文件不改变任何视觉：每个 token 的数值都等于迁移前对应字面量的值，
 * 只是把「几档节奏」显式化，后续调参只需改这里。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

object Motion {
    /** 无动画，用于「关掉动效」以及不该有过渡的瞬时替换 */
    const val Instant = 0

    /** 按下/勾选等即时反馈 */
    const val Fast = 100

    /** 小面积状态变化：透明度、图标、导航指示器 */
    const val Short = 150

    /** 默认过渡的最大公约数 */
    const val Base = 200

    /** 页面内元素进出 */
    const val Medium = 250

    /** 主题配色过渡、底部面板高度变化 */
    const val Long = 300

    /** 大面积内容替换、滑动转场 */
    const val Slower = 350

    /** 展开类大动作 */
    const val Slowest = 400

    /** Material 3 standard：快出慢入，适合大多数属性动画 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Material 3 emphasized：慢入快出，适合大幅位移 */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 两端对称，适合 alpha 淡入淡出 */
    val Balanced: Easing = EaseInOut

    /** 无语义加速，仅用于颜色插值 */
    val Plain: Easing = LinearEasing

    fun <T> instant(): TweenSpec<T> = tween(Instant)

    fun <T> fast(easing: Easing = Standard): TweenSpec<T> = tween(Fast, easing = easing)

    fun <T> short(easing: Easing = Standard): TweenSpec<T> = tween(Short, easing = easing)

    fun <T> base(easing: Easing = Standard): TweenSpec<T> = tween(Base, easing = easing)

    fun <T> medium(easing: Easing = Emphasized): TweenSpec<T> = tween(Medium, easing = easing)

    fun <T> long(easing: Easing = Standard): TweenSpec<T> = tween(Long, easing = easing)

    fun <T> slower(easing: Easing = Standard): TweenSpec<T> = tween(Slower, easing = easing)

    fun <T> slowest(easing: Easing = Standard): TweenSpec<T> = tween(Slowest, easing = easing)
}
