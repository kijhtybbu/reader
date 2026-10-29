/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签仓库。
 *
 * 一个段落最多一个书签（按 nodeId 去重，判定见 `bookmarkForNode`）。
 * 定位本身仍是精确的：存下来的 [Bookmark.nodeIntraOffset] 就是收藏时那一行的段内偏移，跳转用的就是它；
 * 只有"这一段有没有书签"对 nodeId 判断 —— 改字号、行高或左右留白后，同一行的段内起点会变，
 * 用 (nodeId, nodeIntraOffset) 做键会让已收藏的行显示成未收藏，再点一次又多出一条。
 * 代价是同一段落只能有一个书签：在别的一行再收藏，是把这条书签挪过去，而不是新增。
 */

package ua.acclorite.book_story.domain.repository

import ua.acclorite.book_story.domain.model.bookmark.Bookmark

interface BookmarkRepository {
    suspend fun getBookmarks(bookId: Int): Result<List<Bookmark>>

    suspend fun addBookmark(bookmark: Bookmark): Result<Unit>

    suspend fun deleteBookmark(id: Int): Result<Unit>

    suspend fun deleteBookmarksForBook(bookId: Int): Result<Unit>
}
