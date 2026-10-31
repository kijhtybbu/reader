/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：富媒体词条的 WebView 接线（设计 §3.6）。
 *
 * 只覆盖纯字符串那部分（base URL、资源路径、MIME、主题样式、字号换算、链接解码）：
 * `WebView` 本身必须在真机/仪器测试里跑，但上面这些换算错了，真机只会表现为「图不出来」/
 * 「乱码」/「点了发音没反应」，没法定位。两类真实约束各有一条用例：
 *  - 词典 CSS 里的**根相对**路径（OALD9 的 `url('/font/Optima_LT_Medium.ttf')`）没有词典前缀；
 *  - 正文里的相对路径 → `https://dict.local/<dictId>/…`，前缀必须能被剥回去。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryArticleWebViewTest {

    @Test
    fun `the base url carries the dictionary id`() {
        assertEquals("https://dict.local/oald/", dictionaryBaseUrl("oald"))
    }

    @Test
    fun `resource paths keep the dictionary prefix semantics`() {
        // 词条里的相对路径：解析后带词典前缀，剥掉即得 .mdd 键
        assertEquals("oalecd9.css", dictionaryResourcePath("/oald/oalecd9.css", "oald"))
        assertEquals(
            "font/Optima_LT_Medium.ttf",
            dictionaryResourcePath("/oald/font/Optima_LT_Medium.ttf", "oald")
        )
        // CSS 里的根相对路径（真实 OALD9 语料）：没有词典前缀，当作本词典自己的路径
        assertEquals("font/Optima_LT_Medium.ttf", dictionaryResourcePath("/font/Optima_LT_Medium.ttf", "oald"))
        // 百分号编码不在这里解（DictionaryResourceStore 会解一次，解两次会读错键）
        assertEquals("%5Coald9%5Capple.png", dictionaryResourcePath("/oald/%5Coald9%5Capple.png", "oald"))
        // 站点根：没有资源可给
        assertNull(dictionaryResourcePath("/", "oald"))
        assertNull(dictionaryResourcePath("/oald/", "oald"))
        assertNull(dictionaryResourcePath("", "oald"))
    }

    @Test
    fun `mime types follow the extension`() {
        assertEquals("text/css", dictionaryResourceMimeType("oalecd9.css"))
        assertEquals("image/png", dictionaryResourceMimeType("\\oald9\\thumb_apple.PNG"))
        assertEquals("image/svg+xml", dictionaryResourceMimeType("idioms.svg"))
        assertEquals("font/ttf", dictionaryResourceMimeType("font\\Optima_LT_Medium.ttf"))
        assertEquals("font/woff2", dictionaryResourceMimeType("a/b/x.woff2"))
        assertEquals("audio/mpeg", dictionaryResourceMimeType("_apple__uss_2.mp3"))
        assertEquals("audio/wav", dictionaryResourceMimeType("x.wav"))
        // 认不出的扩展名给二进制：WebView 不会把它当文本渲染
        assertEquals("application/octet-stream", dictionaryResourceMimeType("weird.xyz"))
        assertEquals("application/octet-stream", dictionaryResourceMimeType("noextension"))
    }

    @Test
    fun `the injected style forces a transparent background and follows the card color`() {
        val document = themedArticleHtml("<p>apple</p>", "rgb(1,2,3)")

        assertTrue(document.contains("html,body{background:transparent!important"))
        assertTrue(document.contains("color:rgb(1,2,3)!important"))
        assertTrue(document.contains("img{max-width:100%!important;height:auto!important}"))
        assertTrue(document.contains("table{max-width:100%!important}"))
        // 关掉字体放大启发式：字号只由 textZoom 决定
        assertTrue(document.contains("-webkit-text-size-adjust:100%"))
        // 词条正文原样跟在样式后面（词典 CSS 仍能覆盖具体元素）
        assertTrue(document.endsWith("<p>apple</p>"))
    }

    @Test
    fun `text zoom shrinks the 16px webview baseline to the card body size`() {
        // 词典 CSS 的基准字号是 WebView 默认的 16px，卡片正文是 Compose 的 14sp：
        // 88% 才能让 MDX 词条的释义和 ECDICT 卡片的释义一样大。
        assertEquals(88, articleTextZoom())
        // 越界值夹住：再小读不清，再大排版全断
        assertEquals(50, articleTextZoom(0.1f))
        assertEquals(300, articleTextZoom(10f))
    }

    @Test
    fun `text zoom follows the system font scale`() {
        // Compose 的 sp 吃系统字号缩放，WebView 的 CSS px 不吃：不乘 fontScale 时
        // 系统字号调大后，同一张卡片里 WebView 正文会比标题小一圈。
        assertEquals(114, articleTextZoom(1.3f))
        assertEquals(263, articleTextZoom(3f))
    }

    @Test
    fun `in-body audio links decode back to the resource path`() {
        val url = dictAudioUrl("oald", "\\oald9\\_apple__uss_2.mp3")

        assertEquals("\\oald9\\_apple__uss_2.mp3", dictAudioPathFromUrl(url))
        // 不是发音链接 / 缺路径：都不该给出一个可读的路径
        assertNull(dictAudioPathFromUrl("https://dict.local/oald/x.mp3"))
        assertNull(dictAudioPathFromUrl("dict-audio://oald/"))
        assertNull(dictAudioPathFromUrl("dict-audio://oald"))
    }

    @Test
    fun `only same-document fragments are allowed through navigation`() {
        val base = dictionaryBaseUrl("oald")

        assertTrue(isSameDocumentFragment("https://dict.local/oald/#sense-3", base))
        // 同 host 的其它文档：仍然要拦（词条只有一份，跳过去就是空白）
        assertFalse(isSameDocumentFragment("https://dict.local/oald/other.html#x", base))
        // 外链与自定义 scheme
        assertFalse(isSameDocumentFragment("https://example.com/x", base))
        assertFalse(isSameDocumentFragment("dict-audio://oald/x.mp3", base))
    }
}
