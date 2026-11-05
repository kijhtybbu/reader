/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：按书覆盖的排版值模型（设计见 docs/per-book-typography-design.md）。
 *
 * 为什么单独抽一层：阅读页、底部面板的样例预览、12 个设置控件现在都要回答
 * 「当前在编辑哪一套排版」。如果每处直接读 SettingsManager，就永远只有全局一份。
 * 这里把「一套排版」定义成一个不可变值对象，全局与本书各持一份，由
 * ReaderTypographyResolver 决定当前取哪一份。
 *
 * 字段范围刻意与排版 Tab 的高频项一一对应（不含页眉/图片/进度条/系统设置）：
 * 那些项不在调参路径上，一起按书覆盖只会让回归面翻倍。
 */

package ua.acclorite.book_story.data.typography

import androidx.compose.runtime.Immutable
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.ui.reader.model.FontWithName

/**
 * 完整的一套排版值。任何字段都不允许为 null —— 解析层负责把「本书缺失的字段」
 * 先用全局值补齐，UI 与阅读页只面对完整值，不必到处写 `?:`。
 */
@Immutable
data class ReaderTypographyValues(
    val fontFamily: FontWithName,
    val fontThickness: Int,
    val boldFontThickness: Int,
    val italic: Boolean,
    val fontSize: Int,
    val lineHeight: Int,
    val paragraphHeight: Int,
    val letterSpacing: Int,
    val paragraphIndentation: Int,
    val textAlignment: ReaderTextAlignment,
    val sidePadding: Int,
    val verticalPadding: Int
)

/**
 * 某本书的排版记录。
 *
 * 字段可空是有意的：解码时单个坏字段/旧版本缺失字段只会让该字段回退到全局，
 * 而不是丢掉整本书的记录。`enabled=false` 表示「记忆保留，但当前跟随全局」，
 * 对应面板上的「全局 / 本书」切换。
 */
@Immutable
data class BookTypographyRecord(
    val enabled: Boolean = false,
    val fontFamilyId: String? = null,
    val fontThickness: Int? = null,
    val boldFontThickness: Int? = null,
    val italic: Boolean? = null,
    val fontSize: Int? = null,
    val lineHeight: Int? = null,
    val paragraphHeight: Int? = null,
    val letterSpacing: Int? = null,
    val paragraphIndentation: Int? = null,
    val textAlignment: ReaderTextAlignment? = null,
    val sidePadding: Int? = null,
    val verticalPadding: Int? = null
)

enum class ReaderTypographyMode {
    GLOBAL,
    BOOK
}

/**
 * 差异标记的字段标识。UI 只关心「这一项在本书里是否与全局不同」，
 * 不关心具体值，所以用枚举而不是字符串。
 */
enum class ReaderTypographyField {
    FONT_FAMILY,
    FONT_THICKNESS,
    BOLD_FONT_THICKNESS,
    ITALIC,
    FONT_SIZE,
    LINE_HEIGHT,
    PARAGRAPH_HEIGHT,
    LETTER_SPACING,
    PARAGRAPH_INDENTATION,
    TEXT_ALIGNMENT,
    SIDE_PADDING,
    VERTICAL_PADDING
}

/**
 * 把一套完整值快照成记录。用于第一次切到「本书」时复制全局值 ——
 * 整组覆盖的语义要求本书一开始就拥有全部 12 项，否则切回来时会有字段悄悄跟随全局。
 */
fun ReaderTypographyValues.toRecord(enabled: Boolean = true): BookTypographyRecord =
    BookTypographyRecord(
        enabled = enabled,
        fontFamilyId = fontFamily.id,
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
