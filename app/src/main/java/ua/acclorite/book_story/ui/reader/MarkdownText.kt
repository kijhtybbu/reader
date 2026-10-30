/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：Markdown → [AnnotatedString]（第二阶段语法 UX，设计 §8.2）。
 *
 * 语法分析现在由模型直接写 Markdown 讲解（标题 + 要点），客户端只负责渲染。
 * 用仓库已有的 `org.commonmark` 解析，不手写 Markdown 解析器；支持的范围收敛到
 * 模型实际会写的结构：标题、段落、有序/无序列表（含嵌套）、粗体/斜体、行内代码、
 * 围栏代码块、引用块、分隔线。
 *
 * 渲染 = 一次 AST 遍历，样式全部写成 [SpanStyle]，因此：
 *  - 纯 JVM 可测（[markdownToAnnotatedString] 不碰 `android.*`、不读 CompositionLocal）；
 *  - 颜色由 [MarkdownColors] 传进来，UI 侧从 `LocalReaderPalette` 取，夜间预设下不闪白。
 *
 * 结构性换行（块之间空一行、列表项一行一条）是布局的一部分，写在文本里而不是
 * ParagraphStyle：对话框里这段文字是一整块可选中复制的内容，段落间距用行内样式表达不了。
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.theme.LocalReaderPalette

/** 正文默认字号：调用方没给字号时（`TextUnit.Unspecified`）用它算标题的相对大小。 */
private val FallbackBodyFontSize = 16.sp

/** 代码/引用的底色在正文色上叠的透明度。 */
private const val CodeBackgroundAlpha = 0.08f

/** 引用块每段开头的竖线标记。 */
private const val QuoteMarker = "▎ "

/** 分隔线的画法（Markdown 的 `---`）。 */
private const val ThematicBreakText = "———"

/**
 * Markdown 渲染要用的颜色。全部来自 `LocalReaderPalette`，这样对话框在夜间/纯黑预设下
 * 也不会出现主题色「闪白」。
 */
@Immutable
data class MarkdownColors(
    val text: Color,
    val accent: Color,
    val muted: Color,
    val codeBackground: Color
)

/**
 * 渲染 Markdown。
 *
 * @param bodyFontSize 正文字号：标题按它放大（H1 1.3×、H2 1.18×、H3 1.08×）。
 */
internal fun markdownToAnnotatedString(
    markdown: String,
    colors: MarkdownColors,
    bodyFontSize: TextUnit = FallbackBodyFontSize
): AnnotatedString {
    if (markdown.isBlank()) return AnnotatedString("")

    val document = Parser.builder().build().parse(markdown)
    return MarkdownRenderer(colors, bodyFontSize).render(document)
}

/**
 * Markdown 文本。颜色跟随阅读区配色；文字可选中（外面套 `SelectionContainer`）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current
) {
    val palette = LocalReaderPalette.current
    val colors = remember(palette) {
        MarkdownColors(
            text = palette.onBar,
            accent = palette.accent,
            muted = palette.onBarMuted,
            codeBackground = palette.onBar.copy(alpha = CodeBackgroundAlpha)
        )
    }
    val bodyFontSize = if (style.fontSize.isSpecified) style.fontSize else FallbackBodyFontSize
    val text = remember(markdown, colors, bodyFontSize) {
        markdownToAnnotatedString(markdown, colors, bodyFontSize)
    }

    StyledText(text = text, modifier = modifier, style = style)
}

/**
 * AST 遍历器。块级元素之间留一个空行，列表项之间只换行 —— 全部通过「写之前先补分隔符」
 * 完成（`AnnotatedString.Builder` 没有删除 API，不能事后裁剪换行）。
 */
private class MarkdownRenderer(
    private val colors: MarkdownColors,
    private val bodyFontSize: TextUnit
) {

    private val builder = AnnotatedString.Builder()
    private var blockCount = 0

    fun render(document: Node): AnnotatedString {
        renderBlocks(document, quote = false)
        return builder.toAnnotatedString()
    }

    /** 块之间的分隔：第一个块之前不加，之后每个块之前空一行。 */
    private fun beginBlock() {
        if (blockCount > 0) builder.append("\n\n")
        blockCount++
    }

    private fun renderBlocks(parent: Node, quote: Boolean) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                // 容器：自己不算一个块，让里面的段落/列表决定分隔，否则引用块开头会多一行。
                is BlockQuote -> renderBlocks(child, quote = true)

                is Heading -> {
                    beginBlock()
                    heading(child, quote)
                }

                is Paragraph -> {
                    beginBlock()
                    if (quote) builder.append(QuoteMarker)
                    renderInlineChildren(child)
                }

                is BulletList -> {
                    beginBlock()
                    list(child, ordered = false, depth = 0, quote = quote)
                }

                is OrderedList -> {
                    beginBlock()
                    list(child, ordered = true, depth = 0, quote = quote)
                }

                is FencedCodeBlock -> {
                    beginBlock()
                    codeBlock(child.literal)
                }

                is IndentedCodeBlock -> {
                    beginBlock()
                    codeBlock(child.literal)
                }

                is ThematicBreak -> {
                    beginBlock()
                    withStyle(SpanStyle(color = colors.muted)) { builder.append(ThematicBreakText) }
                }

                // Document 之外的未知块：其子节点照常渲染，不吞内容。
                else -> renderBlocks(child, quote)
            }
            child = child.next
        }
    }

    private fun heading(heading: Heading, quote: Boolean) {
        if (quote) builder.append(QuoteMarker)
        val scale = when (heading.level) {
            1 -> 1.3f
            2 -> 1.18f
            3 -> 1.08f
            else -> 1f
        }
        withStyle(
            SpanStyle(
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = bodyFontSize * scale
            )
        ) {
            renderInlineChildren(heading)
        }
    }

    private fun codeBlock(literal: String?) {
        val code = literal?.trimEnd('\n').orEmpty()
        if (code.isEmpty()) return
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = colors.codeBackground,
                color = colors.text
            )
        ) {
            builder.append(code)
        }
    }

    /**
     * 列表。前缀（`• ` / `1. `）用强调色，嵌套层级按两格缩进；
     * 每个列表项自己负责换行（项之间一行，不是空行）。
     */
    private fun list(list: Node, ordered: Boolean, depth: Int, quote: Boolean) {
        val indent = "  ".repeat(depth)
        var startNumber = (list as? OrderedList)?.markerStartNumber ?: 1
        var item = list.firstChild
        var first = true
        while (item != null) {
            if (item is ListItem) {
                if (!first) builder.append("\n")
                first = false
                if (quote && depth == 0) builder.append(QuoteMarker)
                withStyle(SpanStyle(color = colors.accent)) {
                    builder.append(indent + if (ordered) "$startNumber. " else "• ")
                }
                renderListItem(item, depth, quote)
                startNumber++
            }
            item = item.next
        }
    }

    private fun renderListItem(item: Node, depth: Int, quote: Boolean) {
        var child = item.firstChild
        var first = true
        while (child != null) {
            when (child) {
                is Paragraph -> {
                    if (!first) builder.append("\n")
                    renderInlineChildren(child)
                    first = false
                }

                is BulletList -> {
                    builder.append("\n")
                    list(child, ordered = false, depth = depth + 1, quote = quote)
                    first = false
                }

                is OrderedList -> {
                    builder.append("\n")
                    list(child, ordered = true, depth = depth + 1, quote = quote)
                    first = false
                }

                is FencedCodeBlock -> {
                    builder.append("\n")
                    codeBlock(child.literal)
                    first = false
                }

                else -> {
                    if (!first) builder.append("\n")
                    renderInlineChildren(child)
                    first = false
                }
            }
            child = child.next
        }
    }

    private fun renderInlineChildren(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            renderInline(child)
            child = child.next
        }
    }

    private fun renderInline(node: Node) {
        when (node) {
            is Text -> builder.append(node.literal)

            is Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.codeBackground,
                    color = colors.accent
                )
            ) {
                builder.append(node.literal)
            }

            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                renderInlineChildren(node)
            }

            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                renderInlineChildren(node)
            }

            is Link -> withStyle(
                SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)
            ) {
                renderInlineChildren(node)
            }

            // 图片只可能是说明文字里的链接：显示 alt 文本（children），不加载资源。
            is Image -> renderInlineChildren(node)

            is SoftLineBreak -> builder.append(" ")

            is HardLineBreak -> builder.append("\n")

            // 模型偶尔会夹 HTML（`<br>` 之类）：不执行、也不把标签原样摆给用户看。
            else -> renderInlineChildren(node)
        }
    }

    private inline fun withStyle(style: SpanStyle, block: () -> Unit) {
        builder.pushStyle(style)
        block()
        builder.pop()
    }
}
