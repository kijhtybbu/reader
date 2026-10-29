/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.storage.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import ua.acclorite.book_story.data.converter.CategoryConverter

@Entity
@TypeConverters(CategoryConverter::class)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(defaultValue = "") val uuid: String,
    val title: String,
    val author: String,
    val description: String?,
    val filePath: String,
    @ColumnInfo(defaultValue = "0") val currentNodeId: Int,
    @ColumnInfo(defaultValue = "0") val currentNodeIntraOffset: Int,
    @ColumnInfo(defaultValue = "0") val totalNodes: Int,
    @ColumnInfo(defaultValue = "0") val currentOffset: Int,
    @ColumnInfo(defaultValue = "0") val totalLength: Int,
    val image: String? = null,
    @ColumnInfo(defaultValue = "[]") val categories: List<Int>,
    /**
     * fork 新增：TXT 文件的编码（用户手动覆盖或自动探测的结果）。
     *
     * null = 未设置/自动（下次解析时重新探测）。没有默认值，是为了让已有的书
     * 保持「未探测」语义：迁移后旧书会走一次探测，GBK 书因此自愈。
     */
    val charsetName: String? = null,
    /**
     * fork 新增：编码的可信度（[ua.acclorite.book_story.domain.model.book.CharsetConfidence] 的名字）。
     * 存字符串而不是序号：以后增加级别不必再迁移一次数据库；读取时按名字还原，未知值退回 FALLBACK。
     */
    val charsetConfidence: String? = null,

    // Obsolete variables, will be deleted
    // at some point
    val scrollIndex: Int,
    val scrollOffset: Int,
    val progress: Float,
)