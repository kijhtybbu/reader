/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：正文 TextStyle 的单一来源。
 *
 * 为什么要抽出来：阅读页（ReaderScreen）与排版面板的样例预览（ReaderTypographyPreview）
 * 需要同一个样式。之前预览自己抄了一份，结果漏了斜体、也漏了「居中/右对齐时取消首行缩进」
 * 这条规则 —— 预览会在面板里显示书里并不存在的效果。两份实现必然会再次分叉，所以合并成
 * 一个纯函数，并由 ReaderTextStyleTest 锁住两者的一致性。
 *
 * 单位都是 SettingsManager 的原始语义，不是直觉值：
 *  - lineHeightSp 是**增量**：实际行高 = fontSize + lineHeightSp
 *  - letterSpacingHundredths 是 1/100 em
 *  - paragraphIndentation 是 ×6sp 倍率，且在 CENTER/END 对齐下不生效
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

/**
 * 构造正文段落样式。纯函数，无 Compose 依赖，便于单测。
 */
fun readerParagraphTextStyle(
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    fontSizeSp: Int,
    lineHeightDeltaSp: Int,
    letterSpacingHundredths: Int,
    paragraphIndentationSetting: Int,
    textAlignment: ReaderTextAlignment,
    italic: Boolean,
    color: Color
): TextStyle {
    // 居中和右对齐下不做首行缩进（与 ParagraphIndentationOption 的隐藏逻辑一致）
    val firstLineIndent = if (
        textAlignment == ReaderTextAlignment.CENTER ||
        textAlignment == ReaderTextAlignment.END
    ) {
        0.sp
    } else {
        (paragraphIndentationSetting * 6).sp
    }

    return TextStyle(
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        textAlign = textAlignment.textAlignment,
        textIndent = TextIndent(firstLine = firstLineIndent),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = (letterSpacingHundredths / 100f).em,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + lineHeightDeltaSp).sp,
        color = color,
        lineBreak = LineBreak.Paragraph
    )
}

/** 段落间距（dp）：段落设置 ×3dp，且不小于行高的 0.5 倍 —— 与 ReaderScreen 的算法一致 */
fun readerParagraphGapDp(paragraphHeightSetting: Int, lineHeightDeltaSp: Int): Float =
    maxOf(paragraphHeightSetting * 3f, lineHeightDeltaSp * 0.5f)

/** 正文左右留白（dp）：边距设置 ×3dp */
fun readerSidePaddingDp(sidePaddingSetting: Int): Float = sidePaddingSetting * 3f
