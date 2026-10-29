/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import ua.acclorite.book_story.core.ui.UIText

/**
 * 字体来源。
 *
 * fork：用户字体的 Typeface 不能在构造时创建 —— `Font(file = ...)` 是 eager 的，
 * 一个 20 MB 的 CJK 字体按 9 个字重各建一次 Typeface 会直接把启动卡死。所以用户字体只保存索引条目，
 * 真正的 FontFamily 由 UserFontTypefaces 按 (file, weight, italic) 在后台线程建并缓存。
 */
@Immutable
sealed interface FontSource {
    @Immutable
    data class Bundled(val family: FontFamily) : FontSource

    @Immutable
    data class User(val entry: UserFontEntry) : FontSource
}

@Immutable
data class FontWithName(
    val id: String,
    val fontName: UIText,
    val source: FontSource
) {
    /** 内置字体的构造方式保持不变：ReaderData 里的 31 个字体仍然走这个重载。 */
    constructor(id: String, fontName: UIText, family: FontFamily) : this(
        id = id,
        fontName = fontName,
        source = FontSource.Bundled(family)
    )

    val userEntry: UserFontEntry?
        get() = (source as? FontSource.User)?.entry
}
