/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：标题格式标记的回归测试。
 *
 * 现象：EPUB 里标题内嵌 <b>/<i> 时（例如第 20 章标题是 <h1><b>20</b></h1>），
 * 解析器把段落格式标记也插进了标题，而 Header 只存纯文本，于是正文里直接显示
 * 「{{B-START}}20{{B-END}}」。这里锁住「标题只保留文字，段落样式仍然生效」。
 */

package ua.acclorite.book_story.data.parser.content.document

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.acclorite.book_story.domain.model.book.NodeContent

class JsoupDocumentParserHeaderTest {

    private val parser = JsoupDocumentParser()

    @Test
    fun `bold inside a header is stripped from the title`() {
        val nodes = parser.parseDocument(
            document = Jsoup.parse("<html><body><h1><b>20</b></h1><p>Body</p></body></html>"),
            images = emptyMap()
        )

        val header = nodes.filterIsInstance<NodeContent.Header>().single()

        assertEquals("20", header.title)
        assertEquals(NodeContent.HeaderLevel.H1, header.level)
        assertTrue(nodes.none { node ->
            node is NodeContent.Header && node.title.contains("{{")
        })
    }

    @Test
    fun `italic and link markers inside a header are stripped too`() {
        val nodes = parser.parseDocument(
            document = Jsoup.parse(
                """<html><body>
                   <h2><i>Part</i> <a href="https://example.com">One</a></h2>
                   <p>Body</p>
                </body></html>""".trimIndent()
            ),
            images = emptyMap()
        )

        val header = nodes.filterIsInstance<NodeContent.Header>().single()

        assertEquals("Part One", header.title)
        assertEquals(NodeContent.HeaderLevel.H2, header.level)
    }

    @Test
    fun `bold inside a paragraph still becomes a style range`() {
        val nodes = parser.parseDocument(
            document = Jsoup.parse("<html><body><p>This <b>is</b> bold</p></body></html>"),
            images = emptyMap()
        )

        val paragraph = nodes.filterIsInstance<NodeContent.Paragraph>().single()

        assertEquals("This is bold", paragraph.text)
        val bold = paragraph.styles.single { it.style is NodeContent.TextStyle.Bold }
        assertEquals("is", paragraph.text.substring(bold.start, bold.end))
    }
}
