/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：书签。
 *
 * 定位用 (nodeId, nodeIntraOffset) 而不是页码 —— 页码在改字号/换字体后立刻失效。
 * 唯一索引放在这三列上：同一行的同一位置重复添加不会产生两条记录。
 */

package ua.acclorite.book_story.data.storage.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarkentity",
    indices = [
        Index(value = ["bookId", "nodeId", "nodeIntraOffset"], unique = true),
        Index(value = ["bookId"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val bookId: Int,
    val nodeId: Int,
    val nodeIntraOffset: Int,
    /** 冗余存章名与摘录：列表直接显示，不用 join，也不怕章节表重建 */
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long
)
