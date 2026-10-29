/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签领域模型。
 *
 * 定位用 (nodeId, nodeIntraOffset) 而不是页码 —— 页码在改字号/换字体后立刻失效。
 */

package ua.acclorite.book_story.domain.model.bookmark

data class Bookmark(
    val id: Int = 0,
    val bookId: Int,
    val nodeId: Int,
    val nodeIntraOffset: Int,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long
)

/**
 * fork 新增：这一段落（nodeId）的书签，没有则 null。
 *
 * 判断"这一段有没有书签"只用 nodeId，不用 [Bookmark.nodeIntraOffset] —— 后者是跳转用的
 * 精确行位置，改字号后同一行的段内偏移会变。图标指示和去重都必须走这里，
 * 否则两处一旦各写一份判断就会再次漂移。规则与代价见 [ua.acclorite.book_story.domain.repository.BookmarkRepository]。
 *
 * 同一段落若因历史数据存在多条，取 [bookmarks] 中的第一条（列表按 nodeId、nodeIntraOffset 排序，结果稳定）。
 */
internal fun bookmarkForNode(bookmarks: List<Bookmark>, nodeId: Int): Bookmark? =
    bookmarks.firstOrNull { it.nodeId == nodeId }
