/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：富媒体词条 HTML 改写的回归（设计 §3.5）。
 *
 * 用真实词典的片段钉住行为：OALD9 的多口音 `sound://` 发音、字源的 `\pic\...` 图片、
 * 带 `@font-face` 的外链样式表，以及必须剥掉的脚本、`javascript:`/`vbscript:` 与
 * 可导航的 `<base>` / `meta refresh` / `iframe`。断言尽量落在「解析后再读属性」上，
 * 避免把 jsoup 的属性顺序/序列化细节钉死。
 */

package ua.acclorite.book_story.ui.reader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupHtmlRewriterTest {

    @Test
    fun `oald9 sound references become audio candidates`() {
        val html = """
            <span class="phons">
            <a class="sound" href="sound://\oald9\apple__gb_1.mp3"><img class="audio" src="\oald9\speaker.png"></a>
            <a class="sound" href="sound://\oald9\apple__us_1.mp3">美</a>
            <a class="sound" href="sound://\oald9\apple__gb_1.mp3">英</a>
            <img class="audio" data-sound="sound://\oald9\apple__us_1.mp3">
            </span>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")

        val gb = "dict-audio://oald9/%5Coald9%5Capple__gb_1.mp3"
        val us = "dict-audio://oald9/%5Coald9%5Capple__us_1.mp3"
        // 按出现顺序去重：gb 被词头与「英」各引用一次，只留一条候选。
        assertEquals(
            listOf(
                AudioCandidate("\\oald9\\apple__gb_1.mp3", gb),
                AudioCandidate("\\oald9\\apple__us_1.mp3", us)
            ),
            result.audio
        )
        assertFalse(result.html.contains("sound://"))

        val document = Jsoup.parseBodyFragment(result.html)
        assertEquals(
            listOf(gb, us, gb),
            document.select("a[href]").map { it.attr("href") }
        )
        // 非 <a> 元素上的 sound:// 同样改写。
        assertEquals(us, document.selectFirst("img[data-sound]")!!.attr("data-sound"))
        // 图片路径不是 sound://，保持原样交给 base URL。
        assertEquals(
            "\\oald9\\speaker.png",
            document.selectFirst("img.audio[src]")!!.attr("src")
        )
    }

    @Test
    fun `file urls become base relative paths with backslashes kept`() {
        val html = """
            <div>
            <img src="file:///\pic\apple.png" width="80">
            <source src="file:///pic/bear.png">
            <a href="file://\pic\cat.png">cat</a>
            </div>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "ziyuan")
        val document = Jsoup.parseBodyFragment(result.html)

        // 开头的分隔符必须去掉：`\pic\...` 按 URL 规则会解析到站点根，丢掉 <dictId>/。
        assertEquals("pic\\apple.png", document.selectFirst("img")!!.attr("src"))
        assertEquals("pic/bear.png", document.selectFirst("source")!!.attr("src"))
        assertEquals("pic\\cat.png", document.selectFirst("a")!!.attr("href"))
        assertFalse(result.html.contains("file://"))
    }

    @Test
    fun `relative resource paths are not rewritten`() {
        val html = """<img src="\pic\x.png"><link rel="stylesheet" href="oald9.css">"""

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)

        assertEquals("\\pic\\x.png", document.selectFirst("img")!!.attr("src"))
        assertEquals("oald9.css", document.selectFirst("link")!!.attr("href"))
    }

    @Test
    fun `stylesheet rules and font faces survive while imports are removed`() {
        val html = """
            <link rel="stylesheet" type="text/css" href="file:///OALD9.css">
            <style>
            @import url("base.css");
            @import "extra.css" screen;
            @font-face { font-family: "OALD"; src: url("\fonts\oald.woff") format("woff"); }
            .entry > .def { margin: 0 }
            body { font-family: "OALD"; }
            </style>
            <p class="def">apple</p>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)

        assertEquals("OALD9.css", document.selectFirst("link[rel=stylesheet]")!!.attr("href"))
        val css = document.selectFirst("style")!!.data()
        assertFalse(css.contains("@import"))
        assertTrue(css.contains("@font-face"))
        // CSS 里的 url() 不改写；反斜杠在 CSS 里是转义符，取不到图由资源层记日志兜底。
        assertTrue(css.contains("""url("\fonts\oald.woff")"""))
        // > 不能被序列化成 &gt;，否则选择器失效。
        assertTrue(css.contains(".entry > .def"))
        assertTrue(css.contains("""body { font-family: "OALD"; }"""))
        assertTrue(document.selectFirst("style") != null)
        assertEquals("apple", document.selectFirst("p.def")!!.text())
    }

    @Test
    fun `scripts event handlers and javascript links are stripped`() {
        val html = """
            <div onclick="steal()" onmouseover="evil()" style="color:red">
            <script>alert('x')</script>
            <a href="javascript:alert(1)">one</a>
            <a href="JavaScript:alert(2)">two</a>
            <a href="java&#x09;script:alert(3)">three</a>
            <img src="javascript:alert(4)" alt="x">
            <button onclick="go()">go</button>
            </div>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)

        assertFalse(result.html.contains("<script", ignoreCase = true))
        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        assertFalse(result.html.contains("onclick", ignoreCase = true))
        assertFalse(result.html.contains("onmouseover", ignoreCase = true))
        // 链接文字保留，只去掉可执行的 href。
        assertEquals(listOf("one", "two", "three"), document.select("a").map { it.text() })
        assertTrue(document.select("a").none { it.hasAttr("href") })
        // 非链接属性不受影响。
        assertEquals("color:red", document.selectFirst("div")!!.attr("style"))
        assertFalse(document.selectFirst("img")!!.hasAttr("src"))
        assertEquals("x", document.selectFirst("img")!!.attr("alt"))
    }

    @Test
    fun `namespaced anchor tags cannot smuggle script urls`() {
        // OALD9 真实语料里 158 个 `<xhtml:a>`，normalName() 是 `xhtml:a` 而不是 `a`，
        // 按元素名白名单会整片漏掉。
        // 第二条是「大小写 + 内嵌制表符」：URL 解析会删掉 tab/换行再比 scheme，归一化必须同步。
        // （评审样例写成 `JuVa&#x09;Script:`，归一化后是 `juvascript:` —— 本来就不是脚本
        // scheme，所以这里用拼写正确的 `JaVa&#x09;ScRiPt:`。）
        val html = """
            <xhtml:a href="javascript:alert(1)">x</xhtml:a>
            <xhtml:a href="JaVa&#x09;ScRiPt:alert(2)">y</xhtml:a>
            <xhtml:a href="help:keyword">help</xhtml:a>
            <xhtml:a href="sound://\oald9\apple__gb_1.mp3">sound</xhtml:a>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)
        val anchors = document.getElementsByTag("xhtml:a")

        assertEquals(4, anchors.size)
        assertEquals(listOf("x", "y", "help", "sound"), anchors.map { it.text() })
        assertTrue(anchors.take(2).none { it.hasAttr("href") })
        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        // 非脚本 scheme 与发音引用照旧处理。
        assertEquals("help:keyword", anchors[2].attr("href"))
        assertEquals(
            "dict-audio://oald9/%5Coald9%5Capple__gb_1.mp3",
            anchors[3].attr("href")
        )
    }

    @Test
    fun `navigable elements base meta refresh and embeds are removed`() {
        // 真实语料把 `<link>`/`<script>` 放在 `<head>` 里；body fragment 解析后这些标签
        // 同样落在 body 上，所以移除逻辑只需对着 body 做。
        val html = """
            <head>
            <base href="https://evil.example/">
            <meta http-equiv="Refresh" content="0;url=https://evil.example/">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <iframe src="https://evil.example/frame"></iframe>
            <object data="https://evil.example/x.swf"><param name="m" value="v"></object>
            <embed src="https://evil.example/y.swf">
            <p>apple</p>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)

        assertTrue(document.select("base").isEmpty())
        assertTrue(document.select("meta[http-equiv]").isEmpty())
        assertTrue(document.select("iframe, object, embed").isEmpty())
        // 其它 meta 不受影响。
        assertEquals("viewport", document.selectFirst("meta")!!.attr("name"))
        assertFalse(result.html.contains("evil.example"))
        assertEquals("apple", document.selectFirst("p")!!.text())
    }

    @Test
    fun `script urls are stripped from every navigable attribute`() {
        val html = """
            <a href="javascript:alert(1)" title="keep">a</a>
            <img src="vbscript:alert(2)" alt="i">
            <xhtml:img src="javascript:alert(5)" alt="ni">
            <map name="m"><area href="vbscript:msgbox(3)" alt="area"></map>
            <form action="javascript:steal()">
            <button formaction="JaVa&#x09;Script:go()">go</button>
            </form>
            <div data="javascript:payload()">d</div>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val document = Jsoup.parseBodyFragment(result.html)

        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        assertFalse(result.html.contains("vbscript:", ignoreCase = true))
        assertTrue(
            document.select("[href], [src], [action], [formaction], [data]").isEmpty()
        )
        // 元素与文字保留，非 URL 属性不受影响。
        assertEquals("keep", document.selectFirst("a")!!.attr("title"))
        assertEquals("i", document.selectFirst("img")!!.attr("alt"))
        // 命名空间元素同样覆盖：判断靠属性名，不靠 `normalName()`。
        assertEquals(
            "ni",
            document.getElementsByTag("xhtml:img").first()!!.attr("alt")
        )
        assertEquals("area", document.selectFirst("area")!!.attr("alt"))
        assertEquals("go", document.selectFirst("button")!!.text())
        assertEquals("d", document.selectFirst("div")!!.text())
    }

    @Test
    fun `import with data uri leaves no residue and keeps selectors intact`() {
        val html = """
            <style>
            /* 被注释掉的 @import url(gone.css); */
            @import url(data:text/css;base64,AAA);
            @import url("base.css") screen;
            @font-face { font-family: "OALD"; src: url("data:font/woff;base64,BBB") format("woff"); }
            .a > .b { color: red }
            </style>
            <p class="b">b</p>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "oald9")
        val css = Jsoup.parseBodyFragment(result.html).selectFirst("style")!!.data()

        assertFalse(css.contains("@import"))
        // data URI 里的分号不能被当成规则结束，否则留下 `base64,AAA);`。
        assertFalse(css.contains("base64,AAA"))
        assertFalse(css.contains("gone.css"))
        assertFalse(css.contains("/*"))
        // @font-face 的 data URI 不是 @import，原样保留。
        assertTrue(css.contains("""url("data:font/woff;base64,BBB")"""))
        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains(".a > .b"))
        assertTrue(css.contains("color: red"))
    }

    @Test
    fun `empty sound urls are removed and uppercase scheme is recognised`() {
        val html = """<a href="sound://">empty</a><a href="SOUND://\oald9\apple__gb_1.mp3">upper</a>"""

        val result = rewriteArticleHtml(html, "oald9")
        val anchors = Jsoup.parseBodyFragment(result.html).select("a")
        val gb = "dict-audio://oald9/%5Coald9%5Capple__gb_1.mp3"

        // 空路径没有资源可取，属性直接删掉，别留下一个空 href。
        assertFalse(anchors[0].hasAttr("href"))
        assertEquals("empty", anchors[0].text())
        assertEquals(gb, anchors[1].attr("href"))
        assertEquals(
            listOf(AudioCandidate("\\oald9\\apple__gb_1.mp3", gb)),
            result.audio
        )
    }

    @Test
    fun `lookup links and same entry anchors are preserved`() {
        val html = """<a href="juice://lookup/apple">apple</a><a href="#sense2">sense 2</a>"""

        val result = rewriteArticleHtml(html, "oald9")

        assertEquals(html, result.html)
        assertEquals(emptyList<AudioCandidate>(), result.audio)
    }

    @Test
    fun `srcset and inline style urls are left untouched`() {
        val html = """
            <img src="\pic\a.png" srcset="\pic\a.png 1x, \pic\a@2x.png 2x">
            <span style="background-image:url('\pic\bg.png')">x</span>
        """.trimIndent()

        val result = rewriteArticleHtml(html, "ziyuan")
        val document = Jsoup.parseBodyFragment(result.html)

        assertEquals(
            "\\pic\\a.png 1x, \\pic\\a@2x.png 2x",
            document.selectFirst("img")!!.attr("srcset")
        )
        assertEquals(
            "background-image:url('\\pic\\bg.png')",
            document.selectFirst("span")!!.attr("style")
        )
    }

    @Test
    fun `text and entities stay readable after serialization`() {
        val result = rewriteArticleHtml("<p>苹果&nbsp;很甜 &amp; 香</p>", "oald9")

        assertEquals("<p>苹果&nbsp;很甜 &amp; 香</p>", result.html)
    }

    @Test
    fun `blank html yields an empty article`() {
        assertEquals(RewrittenArticle("", emptyList()), rewriteArticleHtml("   ", "oald9"))
    }
}
