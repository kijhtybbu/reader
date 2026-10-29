/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按纵向坐标命中段落节点。
 *
 * 用途：双击翻译。原来每个段落自己挂 combinedClickable，那等于在正文上又放了一套
 * 点击检测器，会把单击吞掉，导致「点右三分之一翻页」在文字区域失效。
 * 现在只有一个检测器（挂在 Pager 上），双击时用这个函数把 y 坐标换算成节点。
 */

package ua.acclorite.book_story.ui.reader

import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.reader.paged.ReaderPage

/**
 * @param y 相对页面上沿的像素坐标（与 Pager 的点击坐标同源）
 */
internal fun readerNodeAtY(page: ReaderPage, y: Float): ReaderNode? {
    if (y < 0f) return null
    var cursor = 0f
    page.items.forEach { item ->
        cursor += item.spaceBefore
        if (y <= cursor + item.height) return item.node
        cursor += item.height
    }
    // 越过最后一项 = 点到页面下方的空白，不应翻译最后一段
    return null
}
