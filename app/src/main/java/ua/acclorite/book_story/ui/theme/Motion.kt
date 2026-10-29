/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：动效 token。
 *
 * 背景：上游把 durationMillis 字面量散落在 38 处（0/100/150/200/250/300/350/400），
 * 想调整整体节奏就得全局搜替换，也无法回答「本 App 的动效有哪几档」。
 *
 * 本文件默认不改变视觉：每个 helper 的默认缓动是 [Legacy]（= Compose `tween` 的默认值
 * FastOutSlowInEasing），迁移前那 38 处全部是裸 `tween(N)`，因此曲线逐一保持不变。
 * 想改用 Material 3 曲线，显式传 [Standard] / [Emphasized]。
 *
 * （这条注释曾经写错过：第一版把默认缓动设成了自定义的 M3 Standard，
 *   相当于在号称「零视觉变化」的提交里悄悄改掉 38 处的曲线。Review 抓出来了。）
 *
 * 未覆盖：本仓库还有 ≥7 处 `spring(...)`（顶栏、Dialog、书籍详情）与若干默认
 * animateDpAsState 的弹簧动画。弹簧参数（stiffness/dampingRatio）应在 Phase 3 做
 * 翻页动效时一并 token 化，避免现在定一套用不上。
 */

package ua.acclorite.book_story.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
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

    /** 默认过渡的中间档 */
    const val Base = 200

    /** 页面内元素进出 */
    const val Medium = 250

    /** 主题配色过渡、底部面板高度变化 */
    const val Long = 300

    /** 大面积内容替换、滑动转场 */
    const val Slower = 350

    /** 展开类大动作 */
    const val Slowest = 400

    /**
     * 迁移前的默认曲线，等于 Compose `tween()` 的默认值。
     * 保持为默认值，任何 token 迁移都不应改变观感。
     */
    val Legacy: Easing = FastOutSlowInEasing

    /**
     * Material 3 standard（cubic-bezier(0.2, 0, 0, 1)）。
     * 起步与收尾都较缓，中段最快；适合属性变化，不适合大幅位移。
     */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * Material 3 emphasized（cubic-bezier(0.05, 0.7, 0.1, 1)）。
     * 起步慢、中后段快、收尾突然放缓；适合大幅位移与转场。
     */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 两端对称，适合 alpha 淡入淡出 */
    val Balanced: Easing = EaseInOut

    /** 无语义加速，仅用于颜色插值 */
    val Plain: Easing = LinearEasing

    fun <T> instant(): TweenSpec<T> = tween(Instant)

    fun <T> fast(easing: Easing = Legacy): TweenSpec<T> = tween(Fast, easing = easing)

    fun <T> short(easing: Easing = Legacy): TweenSpec<T> = tween(Short, easing = easing)

    fun <T> base(easing: Easing = Legacy): TweenSpec<T> = tween(Base, easing = easing)

    fun <T> medium(easing: Easing = Legacy): TweenSpec<T> = tween(Medium, easing = easing)

    fun <T> long(easing: Easing = Legacy): TweenSpec<T> = tween(Long, easing = easing)

    fun <T> slower(easing: Easing = Legacy): TweenSpec<T> = tween(Slower, easing = easing)

    fun <T> slowest(easing: Easing = Legacy): TweenSpec<T> = tween(Slowest, easing = easing)
}
