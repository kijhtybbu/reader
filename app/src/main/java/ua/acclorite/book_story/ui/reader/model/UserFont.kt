/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：用户导入字体的索引条目。
 *
 * 字段与 filesDir/fonts/index.json 一一对应（见 UserFontIndexCodec）。
 * `variable` 与 `hasCjk` 都是导入时检测一次、之后只读的结果：
 *  - variable：文件里是否有 fvar 表，决定字重滑块能否走真实的 wght 轴；
 *  - hasCjk：Paint.hasGlyph 对「汉」「。」的检测，用于列表里的警告徽标。
 */

package ua.acclorite.book_story.ui.reader.model

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.core.ui.UIText

@Immutable
data class UserFontEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val size: Long,
    val variable: Boolean,
    val hasCjk: Boolean,
    val importedAt: Long
)

fun UserFontEntry.toFontWithName(): FontWithName = FontWithName(
    id = id,
    fontName = UIText.StringValue(name),
    source = FontSource.User(this)
)
