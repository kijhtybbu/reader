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
    /** 左三分之一上一页，右三分之一下一页，中间呼出菜单 */
    SIDES(R.string.tap_zones_sides),

    /** 上三分之一呼出菜单，中三分之一下一页，下三分之一上一页 */
    TOP_BOTTOM(R.string.tap_zones_top_bottom),

    /** 只有中间区域呼出菜单，其余区域不响应（避免误触） */
    CENTER_ONLY(R.string.tap_zones_center_only)
}

enum class ReaderTapAction { PREVIOUS, NEXT, MENU, NONE }

/**
 * @param x 点击点相对内容区的横向比例 0..1
 * @param y 点击点相对内容区的纵向比例 0..1
 */
fun tapActionFor(zones: ReaderTapZones, x: Float, y: Float): ReaderTapAction {
    val third = 1f / 3f
    return when (zones) {
        ReaderTapZones.SIDES -> when {
            x < third -> ReaderTapAction.PREVIOUS
            x > 1f - third -> ReaderTapAction.NEXT
            else -> ReaderTapAction.MENU
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
