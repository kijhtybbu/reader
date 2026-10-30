/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：语法分析的 Markdown 渲染（第二阶段语法 UX，设计 §8.2）。
 *
 * 钉住「模型写的 Markdown 会变成用户看得懂的排版」：标题加粗放大、列表带前缀（嵌套有缩进）、
 * 粗体/斜体/行内代码/代码块有对应样式、块之间空一行、引用块带竖线标记。
 * 纯 JVM：只断言 `AnnotatedString` 的文本与 span，不依赖 CompositionLocal。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    private val colors = MarkdownColors(
        text = Color.Black,
        accent = Color.Blue,
        muted = Color.Gray,
        codeBackground = Color.LightGray
    )

    private fun render(markdown: String) = markdownToAnnotatedString(
        markdown = markdown,
        colors = colors,
        bodyFontSize = 16.sp
    )

    @Test
    fun `headings are bold and larger than the body text`() {
        val result = render("# Main clause\n\nThe subject is *It*.")

        assertEquals("Main clause\n\nThe subject is It.", result.text)
        val heading = result.spanStyles.single { it.item.fontSize.value > 16f }
        assertEquals(FontWeight.Bold, heading.item.fontWeight)
        assertEquals(Color.Blue, heading.item.color)
        // 标题的 span 覆盖整段标题文字，正文不受影响。
        assertEquals("Main clause", result.text.substring(heading.start, heading.end))
    }

    @Test
    fun `bullet lists get a prefix per item and a line per item`() {
        val result = render("- main clause\n- subordinate clause")

        assertEquals("• main clause\n• subordinate clause", result.text)
    }

    @Test
    fun `ordered lists are numbered from the markdown start number`() {
        val result = render("3. first\n4. second")

        assertEquals("3. first\n4. second", result.text)
    }

    @Test
    fun `nested lists are indented`() {
        val result = render("- outer\n  - inner")

        assertEquals("• outer\n  • inner", result.text)
    }

    @Test
    fun `bold and italic text are styled inline`() {
        val result = render("The **main** verb is *was*.")

        assertEquals("The main verb is was.", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline code keeps its text and gets a monospace background`() {
        val result = render("The phrase `had been` is past perfect.")

        assertEquals("The phrase had been is past perfect.", result.text)
        val code = result.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(Color.LightGray, code.item.background)
        assertEquals("had been", result.text.substring(code.start, code.end))
    }

    @Test
    fun `fenced code blocks keep their literal text`() {
        val result = render("```\nS + V + O\n```")

        assertEquals("S + V + O", result.text)
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `blocks are separated by a blank line`() {
        val result = render("First paragraph.\n\nSecond paragraph.")

        assertEquals("First paragraph.\n\nSecond paragraph.", result.text)
    }

    @Test
    fun `block quotes are marked with a bar`() {
        val result = render("> The main clause governs the tense.")

        assertEquals("▎ The main clause governs the tense.", result.text)
    }

    @Test
    fun `plain text passes through unchanged`() {
        val result = render("Subject + verb + object.")

        assertEquals("Subject + verb + object.", result.text)
        assertFalse(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `blank markdown renders as empty text`() {
        assertEquals("", render("").text)
        assertEquals("", render("   ").text)
    }
}
