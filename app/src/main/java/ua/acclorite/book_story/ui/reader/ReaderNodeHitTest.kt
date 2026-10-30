/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按纵向坐标命中页项/段落节点。
 *
 * 用途一（双击翻译）：原来每个段落自己挂 combinedClickable，那等于在正文上又放了一套
 * 点击检测器，会把单击吞掉，导致「点右三分之一翻页」在文字区域失效。
 * 现在只有一个检测器（挂在 Pager 上），双击时用 [readerNodeAtY] 把 y 坐标换算成节点。
 *
 * 用途二（S5 取词）：[readerPageItemAtY] 除了节点还交出「点在这一项的哪一行/哪个局部 y」，
 * 取词要用它拿到 `TextLayoutResult` 的身份（节点 id + 页项起点）并判断是否点在行间空隙里。
 *
 * 两个函数的空白处理刻意不同：双击翻译把段间空隙算给下一段（点空隙翻译下一段是合理的），
 * 取词不算 —— 空隙里没有字，命中会把卡片贴到上一段的最后一个词上。
 */

package ua.acclorite.book_story.ui.reader

import ua.acclorite.book_story.ui.reader.model.ReaderNode
import ua.acclorite.book_story.ui.reader.paged.ReaderPage
import ua.acclorite.book_story.ui.reader.paged.ReaderPageItem

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

/**
 * 一次页项命中。
 *
 * @param topInPage 该项文本块顶部相对页面上沿的 y（已经越过 `spaceBefore`）。
 * @param localY 点击点相对文本块顶部的 y；落在段前空隙里时为负值。
 */
internal data class ReaderPageItemHit(
    val item: ReaderPageItem,
    val topInPage: Float,
    val localY: Float
)

/** 与 [readerNodeAtY] 同一套纵向游标，但交出页项本身与局部坐标。 */
internal fun readerPageItemAtY(page: ReaderPage, y: Float): ReaderPageItemHit? {
    if (y < 0f) return null
    var cursor = 0f
    page.items.forEach { item ->
        cursor += item.spaceBefore
        val top = cursor
        if (y <= cursor + item.height) return ReaderPageItemHit(item, top, y - top)
        cursor += item.height
    }
    return null
}
