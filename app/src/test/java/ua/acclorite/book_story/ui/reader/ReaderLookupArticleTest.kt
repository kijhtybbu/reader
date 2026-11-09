/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词卡片正文结构化解析的回归（第二阶段 S5 增强）。
 *
 * 覆盖三类词条：
 *  - 新内置 ECDICT：带 `<!--ecdict|…-->` 元信息与 `<!--def-->` 释义标记；
 *  - 旧内置 ECDICT：没有标记，靠启发式拆；
 *  - 导入的 MDX：简单行内标签会拆，带块级标签的整体交给 fromHtml。
 *
 * 另外钉住富媒体分流（设计 §3.6）：只有「词典带 `.mdd` 资源包」且正文引用了
 * 图片/表格/样式表/发音时才走 `LookupBody.Media`（WebView），否则保持原来的卡片。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupArticleTest {

    @Test
    fun `ecdict entry with markers is split into sections and badges`() {
        val html = "" +
            "<!--ecdict|c=4|o=1|t=cet4,cet6|b=1234|f=2345-->" +
            "<b>apple</b>" +
            "<br><span style=\"color:#888\">/&#x27;æpl/</span>" +
            "<br>n. 苹果, 家伙\\n[医] 苹果" +
            "<br><!--def-->n. fruit with red or yellow or green skin" +
            "<br>n. native Eurasian tree widely cultivated" +
            "<br><small>s:apples</small>"

        val body = parseLookupBody(html, "apple")

        assertTrue(body is LookupBody.Sections)
        body as LookupBody.Sections
        assertEquals("/'æpl/", body.phonetic)
        assertEquals(4, body.meta?.collins)
        assertEquals(true, body.meta?.oxford)
        assertEquals(listOf("cet4", "cet6"), body.meta?.tags)
        // bnc/frq 取更靠前的名次
        assertEquals(1234, body.meta?.frequencyRank)
        assertEquals(
            listOf(
                LookupSense("n.", "苹果, 家伙"),
                LookupSense("[医]", "苹果")
            ),
            body.senses
        )
        assertEquals(
            listOf(
                LookupDefinition("n.", "fruit with red or yellow or green skin"),
                LookupDefinition("n.", "native Eurasian tree widely cultivated")
            ),
            body.definitions
        )
        assertEquals(listOf("词形：复数 apples"), body.notes)
    }

    @Test
    fun `legacy ecdict entry without markers falls back to heuristics`() {
        val html = "<b>result</b>" +
            "<br><span style=\"color:#888\">/ri&#x27;zʌlt/</span>" +
            "<br>n. 结果, 成绩\\nvi. 产生, 结果" +
            "<br>n. something that results" +
            "<br>v. issue or terminate" +
            "<br><small>s:results/p:resulted</small>"

        val body = parseLookupBody(html, "result") as LookupBody.Sections

        assertNull(body.meta)
        assertEquals("/ri'zʌlt/", body.phonetic)
        assertEquals(
            listOf(LookupSense("n.", "结果, 成绩"), LookupSense("vi.", "产生, 结果")),
            body.senses
        )
        assertEquals(
            listOf(
                LookupDefinition("n.", "something that results"),
                LookupDefinition("v.", "issue or terminate")
            ),
            body.definitions
        )
        assertEquals(listOf("词形：复数 results · 过去式 resulted"), body.notes)
    }

    @Test
    fun `bold headword is only dropped when it matches the card title`() {
        val same = parseLookupBody("<b>apple</b><br>n. 苹果", "Apple") as LookupBody.Sections
        assertEquals(listOf(LookupSense("n.", "苹果")), same.senses)

        val different = parseLookupBody("<b>apples</b><br>n. 苹果的复数", "apple")
        different as LookupBody.Sections
        assertEquals(listOf(LookupSense(null, "<b>apples</b>"), LookupSense("n.", "苹果的复数")), different.senses)
    }

    @Test
    fun `entries with block level markup are kept as rich html`() {
        val html = "<div class=\"entry\"><p>apple</p><ul><li>a fruit</li></ul></div>"

        val body = parseLookupBody(html, "apple")

        assertTrue(body is LookupBody.Rich)
        assertEquals(html, (body as LookupBody.Rich).html)
    }

    @Test
    fun `definition marker never leaks into rich html`() {
        val html = "<div class=\"entry\"><b>apple</b><br><!--def-->n. a fruit</div>"

        val body = parseLookupBody(html, "apple") as LookupBody.Rich

        assertFalse(body.html.contains("<!--def-->"))
        assertTrue(body.html.contains("n. a fruit"))
    }

    @Test
    fun `unknown inline tags also fall back to rich html`() {
        val body = parseLookupBody("<b>apple</b><br><kbd>weird</kbd>", "apple")

        assertTrue(body is LookupBody.Rich)
    }

    @Test
    fun `lookup links survive in a sense line`() {
        val html = "<b>apple</b><br>n. 苹果，见 <a href=\"juice://lookup/pear\">pear</a>"

        val body = parseLookupBody(html, "apple") as LookupBody.Sections

        assertEquals(1, body.senses.size)
        assertTrue(body.senses.first().html.contains("juice://lookup/pear"))
    }

    @Test
    fun `meta comment tolerates unknown keys and bad numbers`() {
        val meta = parseLookupMeta("<!--ecdict|z=9|c=9|o=0|t=zk gk|b=0|f=77-->")

        assertNull(meta?.collins)
        assertEquals(false, meta?.oxford)
        assertEquals(listOf("zk", "gk"), meta?.tags)
        assertNull(meta?.bncRank)
        assertEquals(77, meta?.frqRank)
        assertEquals(77, meta?.frequencyRank)
    }

    @Test
    fun `meta comment is stripped from the rendered body`() {
        val body = parseLookupBody(
            "<!--ecdict|c=5--><b>apple</b><br>n. 苹果",
            "apple"
        )

        assertTrue(body is LookupBody.Sections)
        assertFalse(body.toString().contains("ecdict"))
    }

    @Test
    fun `english definitions keep their pos as a separate label`() {
        assertEquals(
            LookupDefinition("n.", "a building above the ground floor"),
            splitDefinition("n. a building above the ground floor")
        )
        assertEquals(
            LookupDefinition("adv.", "so as to be complete"),
            splitDefinition("adv. so as to be complete")
        )
        assertEquals(
            LookupDefinition(null, "Short for \"Maximilian\""),
            splitDefinition("Short for \"Maximilian\"")
        )
    }

    @Test
    fun `bare wordnet pos is only split inside the ecdict definition block`() {
        // ECDICT 有一批释义没写点号（`0` = `n a mathematical element…`），要当词性徽标。
        val html = "<b>sometimes</b>" +
            "<br>adv. 有时\\n偶尔" +
            "<br><!--def-->r on certain occasions or in some specified manner" +
            "<br>r at some indefinite or unstated time"

        val body = parseLookupBody(html, "sometimes") as LookupBody.Sections

        assertEquals(listOf(LookupSense("adv.", "有时"), LookupSense(null, "偶尔")), body.senses)
        assertEquals(
            listOf(
                LookupDefinition("r.", "on certain occasions or in some specified manner"),
                LookupDefinition("r.", "at some indefinite or unstated time")
            ),
            body.definitions
        )
        // 白名单之外的残行（`O.K.` 的 `k an endorsement`）仍当正文
        assertEquals(LookupDefinition(null, "k an endorsement"), splitDefinition("k an endorsement"))
        // 没打 `<!--def-->` 的导入词典不享受这条放宽，避免把 `a piece of …` 当词性
        assertEquals(
            LookupDefinition(null, "a piece of land"),
            splitDefinition("a piece of land")
        )
        assertEquals(
            LookupDefinition("a.", "piece of land"),
            splitDefinition("a piece of land", allowBarePos = true)
        )
    }

    @Test
    fun `sense labels cover parts of speech and domain tags`() {
        assertEquals(LookupSense("vt.", "感觉, 认知"), splitSense("vt. 感觉, 认知"))
        assertEquals(LookupSense("[计]", "结果"), splitSense("[计] 结果"))
        assertEquals(LookupSense(null, "run的过去式和过去分词"), splitSense("run的过去式和过去分词"))
        // `e.g.` 不是词性，不能当标签
        assertEquals(LookupSense(null, "e.g. example"), splitSense("e.g. example"))
    }

    @Test
    fun `plain text strips tags and common entities`() {
        assertEquals("apple 'n' pear", plainText("<b>apple</b> &#x27;n&#x27; pear"))
    }

    /* ------ 富媒体分流（设计 §3.6）------ */

    @Test
    fun `media routing needs both a resource pack and a media marker`() {
        // 有资源包 + 命中标记 → WebView
        assertTrue(shouldUseMediaRenderer("<img src=\"thumb_apple.png\">", hasResources = true))
        assertTrue(shouldUseMediaRenderer("<TABLE><tr><td>x</td></tr></TABLE>", hasResources = true))
        assertTrue(shouldUseMediaRenderer("<link REL=\"stylesheet\" href=\"oalecd9.css\">", true))
        assertTrue(shouldUseMediaRenderer("<a href=\"sound://apple__gb_1.mp3\">x</a>", true))
        // 没有资源包：断链只会把能读的卡片变成空白，保持结构化卡片
        assertFalse(shouldUseMediaRenderer("<img src=\"thumb_apple.png\">", hasResources = false))
        // 有资源包但正文没有媒体标记：纯文本词条继续走卡片
        assertFalse(shouldUseMediaRenderer("<b>apple</b><br>n. 苹果", hasResources = true))
        // 不是样式表的 link 不算
        assertFalse(shouldUseMediaRenderer("<link rel=\"icon\" href=\"a.ico\">", true))
    }

    @Test
    fun `entries with resource packs and a stylesheet link render as media`() {
        // 真实牛津高阶 9 的形态：`<link>`（fragment 解析后落在 body）+ `sound://` 发音 + 图片。
        val html = "<link rel=\"stylesheet\" href=\"oalecd9.css\">" +
            "<h-g><pron>/ˈæpl/</pron></h-g>" +
            "<div class=\"entry\"><img src=\"thumb_apple.png\">" +
            "<xhtml:a data-sound=\"sound://\\oald9\\_apple__uss_2.mp3\">play</xhtml:a></div>"

        val body = parseLookupBody(html, "apple", hasResources = true, dictId = "oald")

        assertTrue(body is LookupBody.Media)
        body as LookupBody.Media
        assertEquals("/ˈæpl/", body.phonetic)
        assertEquals(listOf("\\oald9\\_apple__uss_2.mp3"), body.audio.map { it.path })
        // sound:// 已换成自定义 scheme（路径整体编码，避免反斜杠被拆成多段）
        assertEquals(
            "dict-audio://oald/%5Coald9%5C_apple__uss_2.mp3",
            body.audio.single().url
        )
        assertFalse(body.html.contains("sound://"))
        assertTrue(body.html.contains("dict-audio://oald/"))
        // 样式表链接与图片原样保留，交给 base URL 解析
        assertTrue(body.html.contains("oalecd9.css"))
        assertTrue(body.html.contains("thumb_apple.png"))
    }

    @Test
    fun `media entries without resource packs keep the fallback card`() {
        val html = "<div class=\"entry\"><pron>/ˈæpl/</pron><img src=\"thumb_apple.png\"></div>"

        // 默认 hasResources = false：现有调用（与没有 .mdd 的词典）不受影响
        val body = parseLookupBody(html, "apple")

        assertTrue(body is LookupBody.Rich)
        assertFalse((body as LookupBody.Rich).html.contains("dict-audio://"))
        // 块级回退也要带上音标（旧实现这里写的是 phonetic = null）
        assertEquals("/ˈæpl/", body.phonetic)
    }

    @Test
    fun `block level fallback keeps the phonetic`() {
        val html = "<div class=\"head\"><span class=\"phon\">/ˈæpl/</span></div>" +
            "<div class=\"entry\"><p>apple</p><ul><li>a fruit</li></ul></div>"

        val body = parseLookupBody(html, "apple") as LookupBody.Rich

        assertEquals("/ˈæpl/", body.phonetic)
        // 正文原样保留（只是改成 Rich 渲染）
        assertTrue(body.html.contains("<li>a fruit</li>"))
    }

    @Test
    fun `bracket labels are not mistaken for phonetics`() {
        // 真实语料里块级/富媒体词条会出现 <span>[U]</span>、<div>[1]</div>、[sing.]
        for (label in listOf("[U]", "[C]", "[1]", "[sing.]", "[pl.]", "[ 12 ]")) {
            val html = "<div class=\"cf\"><span>$label</span></div><div>apple</div>"
            val body = parseLookupBody(html, "apple") as LookupBody.Rich
            assertNull("$label must not become the phonetic", body.phonetic)
        }
    }

    @Test
    fun `bracket phonetics survive the label filter`() {
        val html = "<div class=\"head\"><span class=\"phon\">[ˈæpl]</span></div>" +
            "<div class=\"entry\"><p>apple</p></div>"

        val body = parseLookupBody(html, "apple") as LookupBody.Rich

        assertEquals("[ˈæpl]", body.phonetic)

        // 老式 DJ 音标没有 IPA 附标也不能被过滤掉
        val djs = "<div class=\"head\"><span class=\"phon\">[ei]</span></div><div>x</div>"
        assertEquals("[ei]", (parseLookupBody(djs, "a") as LookupBody.Rich).phonetic)
    }

    @Test
    fun `favorite definition keeps the leading senses and strips markup`() {
        val html = "<b>apple</b>" +
            "<br><span style=\"color:#888\">/ˈæpl/</span>" +
            "<br>n. 苹果, 家伙" +
            "<br>[医] 苹果" +
            "<br>n. 苹果树" +
            "<br><!--def-->n. fruit with red or yellow or green skin"

        val body = parseLookupBody(html, "apple") as LookupBody.Sections

        // 只取前三条义项，保留词性标签；HTML 标签不会漏进收藏列表。
        assertEquals("n. 苹果, 家伙；[医] 苹果；n. 苹果树", body.favoriteDefinition())
    }

    @Test
    fun `favorite definition drops style blocks from a rich entry`() {
        val html = "<style>.x{color:red}</style>" +
            "<div class=\"entry\">Hello <b>world</b></div>"

        val body = parseLookupBody(html, "hello") as LookupBody.Rich

        assertEquals("Hello world", body.favoriteDefinition())
    }

    @Test
    fun `favorite definition is truncated and returns null when there is nothing to show`() {
        val long = LookupBody.Rich(null, null, "<div>" + "a".repeat(500) + "</div>")
        assertTrue(long.favoriteDefinition(maxLength = 10)!!.endsWith("…"))
        assertEquals(11, long.favoriteDefinition(maxLength = 10)!!.length)

        val empty = parseLookupBody("", "apple")
        assertNull(empty.favoriteDefinition())
    }
}
