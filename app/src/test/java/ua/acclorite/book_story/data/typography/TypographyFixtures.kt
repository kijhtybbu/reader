/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书排版的测试夹具。
 *
 * 用 FontFamily.Default + 固定 id 构造字体，避免单测依赖 Android 资源。
 * 每个字段给不同值，才能验证「回退到全局」是不是真的按字段生效。
 */

package ua.acclorite.book_story.data.typography

import androidx.compose.ui.text.font.FontFamily
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.ui.reader.model.FontWithName

internal fun typographyFont(id: String): FontWithName =
    FontWithName(
        id = id,
        fontName = UIText.StringValue("Font $id"),
        family = FontFamily.Default
    )

internal fun typographyValues(
    fontId: String = "builtin",
    fontThickness: Int = 400,
    boldFontThickness: Int = 300,
    italic: Boolean = false,
    fontSize: Int = 16,
    lineHeight: Int = 4,
    paragraphHeight: Int = 8,
    letterSpacing: Int = 0,
    paragraphIndentation: Int = 5,
    textAlignment: ReaderTextAlignment = ReaderTextAlignment.JUSTIFY,
    sidePadding: Int = 6,
    verticalPadding: Int = 0
): ReaderTypographyValues = ReaderTypographyValues(
    fontFamily = typographyFont(fontId),
    fontThickness = fontThickness,
    boldFontThickness = boldFontThickness,
    italic = italic,
    fontSize = fontSize,
    lineHeight = lineHeight,
    paragraphHeight = paragraphHeight,
    letterSpacing = letterSpacing,
    paragraphIndentation = paragraphIndentation,
    textAlignment = textAlignment,
    sidePadding = sidePadding,
    verticalPadding = verticalPadding
)
