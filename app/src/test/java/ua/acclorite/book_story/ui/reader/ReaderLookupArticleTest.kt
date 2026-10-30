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
}
