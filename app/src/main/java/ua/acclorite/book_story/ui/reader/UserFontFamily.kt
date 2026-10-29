/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按 (font, weight, italic) 解析出可用的 FontFamily。
 *
 * 内置字体返回构造时已经建好的 family；用户字体第一次被组合时在 Dispatchers.Default 上
 * 建 Typeface（20 MB CJK 字体 100–300 ms），建好前先用默认字体占位，完成后触发重组。
 * 这样阅读页不用阻塞在字体加载上，也不会因为 Font(file) 的 eager 构造把字号/字重设置
 * 各加载一遍。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import ua.acclorite.book_story.ui.reader.data.UserFontTypefaces
import ua.acclorite.book_story.ui.reader.model.FontSource
import ua.acclorite.book_story.ui.reader.model.FontWithName

@Composable
fun rememberFontFamily(
    font: FontWithName,
    fontWeight: Int,
    italic: Boolean
): FontFamily {
    val bundled = (font.source as? FontSource.Bundled)?.family
    if (bundled != null) return bundled

    val entry = (font.source as FontSource.User).entry
    val context = LocalContext.current
    val cached = remember(font.id, fontWeight, italic) {
        UserFontTypefaces.cached(entry, fontWeight, italic)
    }

    val state by produceState(
        initialValue = cached ?: FontFamily.Default,
        font.id,
        fontWeight,
        italic
    ) {
        value = cached ?: UserFontTypefaces.resolve(context, entry, fontWeight, italic)
    }
    return state
}
