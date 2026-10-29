/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：内置字体 + 用户字体的统一入口。
 *
 * 存在的理由：ReaderData.fonts 仍是 31 个内置字体的唯一来源，但只要用户导入过字体，
 * 字体选择器、设置反序列化就必须同时看到两份。把合并逻辑收在这里，避免每个调用点
 * 各写一遍 `ReaderData.fonts + ...`。
 */

package ua.acclorite.book_story.ui.reader.data

import ua.acclorite.book_story.ui.reader.model.FontWithName
import ua.acclorite.book_story.ui.reader.model.toFontWithName

object FontRegistry {

    /**
     * 读取 UserFontRegistry 的 snapshot state，所以 Compose 在导入/删除后会自动重组。
     */
    val fonts: List<FontWithName>
        get() = ReaderData.fonts + UserFontRegistry.fonts.map { it.toFontWithName() }

    /**
     * 反序列化路径：找不到（字体被删、索引损坏）时回落到默认字体，与改动前的行为一致。
     */
    fun findById(id: String): FontWithName =
        fonts.find { it.id == id } ?: ReaderData.fonts[0]
}
