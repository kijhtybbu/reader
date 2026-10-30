/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：取词的词形归一（第二阶段 S5）。
 *
 * 这些断言盯的是「点了却什么都没查到」和「查了个标点」两类问题：
 * ICU 断词给出的是片段，不是词典词头；CJK 一断就是半句话。
 */

package ua.acclorite.book_story.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderLookupWordTest {

    @Test
    fun `surrounding punctuation and quotes are trimmed`() {
        assertEquals("apple", normalizeLookupWord("“apple,”"))
        assertEquals("word", normalizeLookupWord("(word)"))
        assertEquals("hello", normalizeLookupWord("hello!"))
        assertEquals("word", normalizeLookupWord("«word»"))
    }

    @Test
    fun `inner apostrophes and hyphens are kept`() {
        assertEquals("don't", normalizeLookupWord("don't"))
        assertEquals("don’t", normalizeLookupWord("don’t"))
        assertEquals("well-known", normalizeLookupWord("well-known"))
        // 首尾的连字符/撇号仍然要剥掉，否则 `--` 会被当成词
        assertEquals("word", normalizeLookupWord("--word--"))
    }

    @Test
    fun `digits are part of the word`() {
        assertEquals("2024", normalizeLookupWord("2024"))
        assertEquals("H2O", normalizeLookupWord("H2O."))
    }

    @Test
    fun `cjk runs are truncated to the maximum word length`() {
        val sentence = "他是一个很好的人啊"
        assertEquals(sentence.take(MAX_CJK_WORD_LENGTH), normalizeLookupWord(sentence))
        assertEquals("汉字", normalizeLookupWord("汉字"))
    }

    @Test
    fun `latin words longer than the cjk limit are not truncated`() {
        val word = "internationalization"
        assertEquals(word, normalizeLookupWord(word))
    }

    @Test
    fun `punctuation only and blank fragments yield nothing`() {
        assertNull(normalizeLookupWord(""))
        assertNull(normalizeLookupWord("   "))
        assertNull(normalizeLookupWord("—"))
        assertNull(normalizeLookupWord("--"))
        assertNull(normalizeLookupWord("“”"))
    }

    @Test
    fun `the reported range covers only the normalized word`() {
        val word = lookupWordIn("(“apple”)")
        assertEquals("apple", word?.text)
        assertEquals(2, word?.start)
        assertEquals(7, word?.end)
    }

    @Test
    fun `the reported range shrinks when cjk is truncated`() {
        val word = lookupWordIn("他是一个很好的人啊")
        assertEquals(MAX_CJK_WORD_LENGTH, word?.end?.minus(requireNotNull(word).start))
    }

    @Test
    fun `lookup links are decoded`() {
        assertEquals("汉字", lookupWordFromLink("juice://lookup/%E6%B1%89%E5%AD%97"))
        assertEquals("a b", lookupWordFromLink("juice://lookup/a%20b"))
    }

    @Test
    fun `non lookup links and broken encoding are ignored`() {
        assertNull(lookupWordFromLink("https://example.com"))
        assertNull(lookupWordFromLink("juice://lookup/"))
        assertNull(lookupWordFromLink("juice://lookup/%E6%B1"))
    }

    @Test
    fun `a multi-word selection looks up its first word only`() {
        // 整串短语查不到，会把「查词」变成「未找到」
        assertEquals("hello", firstLookupWord("hello world"))
        assertEquals("hello", firstLookupWord("  hello, world  "))
        assertEquals("hello", firstLookupWord("“hello” world"))
        assertEquals("don't", firstLookupWord("don't stop"))
        assertEquals("well-known", firstLookupWord("well-known fact"))
        assertEquals("汉字", firstLookupWord("汉字 词语"))
    }

    @Test
    fun `a single word selection behaves like normalizing it`() {
        assertEquals(normalizeLookupWord("“apple,”"), firstLookupWord("“apple,”"))
        assertEquals("apple", firstLookupWord("apple"))
        // CJK 截断规则对首个词同样生效
        assertEquals(
            MAX_CJK_WORD_LENGTH,
            firstLookupWord("他是一个很好的人啊")?.length
        )
    }

    @Test
    fun `a selection without any word yields nothing`() {
        assertNull(firstLookupWord(""))
        assertNull(firstLookupWord("   "))
        assertNull(firstLookupWord("— …"))
    }
}
