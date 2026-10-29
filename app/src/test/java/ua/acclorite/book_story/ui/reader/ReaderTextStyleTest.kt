/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：正文样式的行为锁定。
 *
 * 存在的理由：排版面板的样例预览曾经自己抄了一份正文样式，漏掉「斜体」和
 * 「居中/右对齐时取消首行缩进」两条规则，于是在面板里显示书里并不存在的效果。
 * 现在两处共用 readerParagraphTextStyle，这些用例把它的语义钉住。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment

class ReaderTextStyleTest {

    private fun style(
        alignment: ReaderTextAlignment = ReaderTextAlignment.JUSTIFY,
        italic: Boolean = false,
        fontSize: Int = 16,
        lineHeightDelta: Int = 4,
        letterSpacing: Int = 0,
        indentation: Int = 5
    ) = readerParagraphTextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight(400),
        fontSizeSp = fontSize,
        lineHeightDeltaSp = lineHeightDelta,
        letterSpacingHundredths = letterSpacing,
        paragraphIndentationSetting = indentation,
        textAlignment = alignment,
        italic = italic,
        color = Color.Black
    )

    @Test
    fun `line height is fontSize plus the delta setting`() {
        // 默认 16 + 4 = 20sp；这是 SettingsManager 的真实语义，不是「倍数」
        assertEquals(20.sp, style().lineHeight)
        assertEquals(28.sp, style(fontSize = 24, lineHeightDelta = 4).lineHeight)
    }

    @Test
    fun `letter spacing is hundredths of an em`() {
        assertEquals(0f, style(letterSpacing = 0).letterSpacing.value, 0.0001f)
        assertEquals(0.02f, style(letterSpacing = 2).letterSpacing.value, 0.0001f)
    }

    @Test
    fun `first line indent is six sp per setting step`() {
        val indent = style(indentation = 5).textIndent?.firstLine
        assertEquals(30.sp, indent)
    }

    @Test
    fun `centered and end aligned paragraphs are not indented`() {
        assertEquals(0.sp, style(alignment = ReaderTextAlignment.CENTER).textIndent?.firstLine)
        assertEquals(0.sp, style(alignment = ReaderTextAlignment.END).textIndent?.firstLine)
        // 起首对齐与两端对齐仍然缩进
        assertEquals(30.sp, style(alignment = ReaderTextAlignment.START).textIndent?.firstLine)
        assertEquals(30.sp, style(alignment = ReaderTextAlignment.JUSTIFY).textIndent?.firstLine)
    }

    @Test
    fun `italic setting is carried into the style`() {
        assertEquals(FontStyle.Italic, style(italic = true).fontStyle)
        assertEquals(FontStyle.Normal, style(italic = false).fontStyle)
    }

    @Test
    fun `alignment and line break are applied`() {
        assertEquals(TextAlign.Justify, style().textAlign)
        assertEquals(TextAlign.Center, style(alignment = ReaderTextAlignment.CENTER).textAlign)
        assertEquals(LineBreak.Paragraph, style().lineBreak)
    }

    @Test
    fun `paragraph gap never drops below half the line height delta`() {
        // 段距设置 ×3dp，但不小于行高增量的 0.5 倍
        assertEquals(24f, readerParagraphGapDp(paragraphHeightSetting = 8, lineHeightDeltaSp = 4), 0.001f)
        assertEquals(12f, readerParagraphGapDp(paragraphHeightSetting = 0, lineHeightDeltaSp = 24), 0.001f)
    }

    @Test
    fun `side padding is three dp per setting step`() {
        assertEquals(18f, readerSidePaddingDp(6), 0.001f)
    }
}
