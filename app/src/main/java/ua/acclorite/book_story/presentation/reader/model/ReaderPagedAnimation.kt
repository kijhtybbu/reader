/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：翻页动画模式。
 *
 * 只保留两种：
 *  - SLIDE：Pager 默认的整页跟手平移
 *  - FADE：两页抵消位移后交叉淡变
 *
 * **刻意不做「覆盖」**：覆盖要求「离场页保持不动、新页滑入盖住它」，而
 * Pager 的 currentPage 在拖动过中点时就会翻转，光凭 (index, currentPage, offsetFraction)
 * 无法可靠判断「这次手势是从哪一页开始的」。写出来的版本在翻转点会跳半页，
 * 或者在两页之间露出背景条 —— 两种都是肉眼可见的破绽，不如不做。
 * 想要「页面替换」的观感，FADE 已经能提供，且数学上无歧义。
 *
 * 也不做「无动画」：翻页是跟手拖拽，拖拽本身就是动画，二者互斥。
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R
import kotlin.math.abs

enum class ReaderPagedAnimation(@StringRes val title: Int) {
    /** 默认：整页随手指平移，与 Compose Pager 的默认行为一致 */
    SLIDE(R.string.paged_animation_slide),

    /** 淡入淡出：两页抵消位移后交叉淡变，无位移 */
    FADE(R.string.paged_animation_fade)
}

/**
 * 单页在翻页过程中的额外变换。
 *
 * 关键约定（来自 Compose 源码 `PagerState.getOffsetDistanceInPages`）：
 *   offsetDistance = page - currentPage - currentPageOffsetFraction
 *   正数表示该页在阅读方向的前方
 * Pager 自己把页面画在 `offsetDistance * pageSize` 处（reverseLayout 时取反），
 * 所以「抵消位移」= 加上 `-offsetDistance * pageSize`。
 *
 * 第一版把 offsetFraction 的符号写反了（`+` 而非 `-`），表现为拖动到一半时
 * 离场页跳半页、两页之间露出背景色 —— 这类错误不会崩溃，只会在真机上「看着不对」，
 * 所以约定写在这里，并由 PagerPageTransformTest 钉住。
 */
data class PagerPageTransform(val translation: Float, val alpha: Float)

fun pagerPageTransform(
    animation: ReaderPagedAnimation,
    /** page - currentPage - currentPageOffsetFraction */
    offsetDistance: Float,
    pageSize: Float,
    /** reverseLayout 为 true 时 Pager 的位移方向相反，抵消项也要反号 */
    reversed: Boolean
): PagerPageTransform {
    val direction = if (reversed) -1f else 1f
    return when (animation) {
        ReaderPagedAnimation.SLIDE -> PagerPageTransform(translation = 0f, alpha = 1f)

        ReaderPagedAnimation.FADE -> PagerPageTransform(
            translation = -offsetDistance * pageSize * direction,
            alpha = (1f - abs(offsetDistance)).coerceIn(0f, 1f)
        )
    }
}
