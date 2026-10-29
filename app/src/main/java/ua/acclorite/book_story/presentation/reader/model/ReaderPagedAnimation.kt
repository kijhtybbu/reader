/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻页动画模式。
 *
 * 只做三种，没有「无动画」：翻页是跟手拖拽，拖拽过程本身就是动画，
 * 「无动画」与「跟手」在交互上互相矛盾。想要瞬时切换请用点击分区翻页（Phase 3 后续）。
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R

enum class ReaderPagedAnimation(@StringRes val title: Int) {
    /** 默认：整页随手指平移，与 Compose Pager 的默认行为一致 */
    SLIDE(R.string.paged_animation_slide),

    /** 覆盖：当前页保持不动，下一页从边缘滑入盖住它 */
    COVER(R.string.paged_animation_cover),

    /** 淡入淡出：两页交叉淡变，无位移 */
    FADE(R.string.paged_animation_fade)
}

/**
 * 单页在翻页过程中的变换。
 *
 * Pager 自己已经把页面平移了 `offset * pageWidth`，所以这里返回的是**额外**要加的变换：
 *  - 平移：什么都不加
 *  - 覆盖：离场页（offset < 0）抵消掉 Pager 的位移 → 视觉上静止，新页滑过来盖住它
 *  - 淡入淡出：两页都抵消位移，只按 |offset| 交叉淡变
 *
 * 抽成纯函数是为了能单测 —— graphicsLayer 里的数学错了只会「看起来怪」，不会报错。
 */
data class PagerPageTransform(val translationX: Float, val alpha: Float)

fun pagerPageTransform(
    animation: ReaderPagedAnimation,
    offset: Float,
    pageWidth: Float
): PagerPageTransform = when (animation) {
    ReaderPagedAnimation.SLIDE -> PagerPageTransform(translationX = 0f, alpha = 1f)

    ReaderPagedAnimation.COVER -> PagerPageTransform(
        translationX = if (offset < 0f) -offset * pageWidth else 0f,
        alpha = 1f
    )

    ReaderPagedAnimation.FADE -> PagerPageTransform(
        translationX = -offset * pageWidth,
        alpha = (1f - kotlin.math.abs(offset)).coerceIn(0f, 1f)
    )
}
