/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：点击分区。
 *
 * 改动前，正文区域的点击只有一个行为：切换菜单（ReaderLayoutPaged 的 noRippleClickable）。
 * 没有「点左边翻上一页」这类常见阅读器操作。
 *
 * 分区命中逻辑抽成纯函数 tapActionFor，因为它一旦算错就是「点左边却翻到下一页」
 * 这种极难在自测中发现、又极其烦人的 bug。
 */

package ua.acclorite.book_story.presentation.reader.model

import androidx.annotation.StringRes
import ua.acclorite.book_story.R

enum class ReaderTapZones(@StringRes val title: Int) {
    /**
     * Kindle 式（默认）：顶部一条呼出菜单，左侧窄区上一页，右侧宽区下一页。
     * 位置性分区（左 = 上一页），反向阅读方向由 [pageDeltaFor] 翻转页码增量。
     */
    KINDLE(R.string.tap_zones_kindle),

    /** 上三分之一呼出菜单，中三分之一下一页，下三分之一上一页 */
    TOP_BOTTOM(R.string.tap_zones_top_bottom),

    /** 只有中间区域呼出菜单，其余区域不响应（避免误触） */
    CENTER_ONLY(R.string.tap_zones_center_only)
}

enum class ReaderTapAction { PREVIOUS, NEXT, MENU, NONE }

/** Kindle 式分区：顶部呼出菜单的带宽（占正文区高度）。 */
private const val KindleMenuTopFraction = 0.12f

/** Kindle 式分区：左侧「上一页」区的宽度（占正文区宽度），其余是「下一页」。 */
private const val KindlePreviousWidthFraction = 0.25f

/**
 * @param x 点击点相对内容区的横向比例 0..1
 * @param y 点击点相对内容区的纵向比例 0..1
 */
fun tapActionFor(zones: ReaderTapZones, x: Float, y: Float): ReaderTapAction {
    val third = 1f / 3f
    return when (zones) {
        // fork: 分区是**位置性**的（左/右、上/中/下，与设置页文案一致），
        // 「左边」永远得到 PREVIOUS，不随阅读方向变化。阅读方向翻转的是下面
        // pageDeltaFor 算出来的页码增量，而不是分区本身。
        ReaderTapZones.KINDLE -> when {
            // Kindle 的顶部工具栏区：约屏幕顶部 12%（在正文区内）呼出菜单。
            y < KindleMenuTopFraction -> ReaderTapAction.MENU
            // 左边小、右边大：左侧 25% 上一页，其余 75% 下一页。
            x < KindlePreviousWidthFraction -> ReaderTapAction.PREVIOUS
            else -> ReaderTapAction.NEXT
        }

        ReaderTapZones.TOP_BOTTOM -> when {
            y < third -> ReaderTapAction.MENU
            y > 1f - third -> ReaderTapAction.PREVIOUS
            else -> ReaderTapAction.NEXT
        }

        ReaderTapZones.CENTER_ONLY -> when {
            x < third || x > 1f - third || y < third || y > 1f - third -> ReaderTapAction.NONE
            else -> ReaderTapAction.MENU
        }
    }
}

/**
 * 把位置性动作解析成页码增量。
 *
 * @param reversed pager 的实际反向状态（= 传给 Pager 的 reverseLayout）。
 * 反向布局下页码向左/向上递增，「左边的下一页」在索引上其实是 -1，
 * 所以翻转的是 delta，不是分区。[ReaderTapAction.MENU] / [ReaderTapAction.NONE] 不翻页，恒为 0。
 */
fun pageDeltaFor(action: ReaderTapAction, reversed: Boolean): Int = when (action) {
    ReaderTapAction.NEXT -> if (reversed) -1 else 1
    ReaderTapAction.PREVIOUS -> if (reversed) 1 else -1
    ReaderTapAction.MENU, ReaderTapAction.NONE -> 0
}
