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
