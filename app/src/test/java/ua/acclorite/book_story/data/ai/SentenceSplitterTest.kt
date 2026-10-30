/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：整段正文切句（第二阶段语法 UX，设计 §8.2）。
 *
 * 这里钉的是 `java.text.BreakIterator` 在 JVM 上的实际行为，不但包括「应该怎么切」，
 * 也包括已知限制（缩写后仍会断开）—— 换实现时这些用例会立刻指出行为变化。
 * 注意：设备上 Android 用的是 ICU 实现，缩写这类边缘情形的切法可能与 JVM 不同；
 * 产品上可接受（每句单独分析，多切一刀只是多一个列表项），但不要把这些断言当成设备行为。
 */

package ua.acclorite.book_story.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `a single sentence comes back as one trimmed entry`() {
        assertEquals(
            listOf("The quick brown fox jumps over the lazy dog."),
            splitIntoSentences("  The quick brown fox jumps over the lazy dog.  ")
        )
    }

    @Test
    fun `multiple sentences are split and trimmed`() {
        assertEquals(
            listOf(
                "It was the best of times, it was the worst of times.",
                "We had everything before us, we had nothing before us."
            ),
            splitIntoSentences(
                "It was the best of times, it was the worst of times.\n" +
                    "We had everything before us, we had nothing before us."
            )
        )
    }

    @Test
    fun `paragraph breaks do not produce empty entries`() {
        assertEquals(
            listOf("First sentence.", "Second paragraph starts here.", "And ends here."),
            splitIntoSentences("First sentence.\n\nSecond paragraph starts here. And ends here.")
        )
    }

    @Test
    fun `a sentence without terminal punctuation is not dropped`() {
        assertEquals(listOf("no punctuation here"), splitIntoSentences("no punctuation here"))
    }

    @Test
    fun `cjk full width punctuation is a sentence boundary`() {
        assertEquals(
            listOf("他去了北京。", "天气很好！", "你吃了吗？"),
            splitIntoSentences("他去了北京。天气很好！你吃了吗？")
        )
    }

    @Test
    fun `mixed scripts keep their own boundaries`() {
        assertEquals(
            listOf("Hello world.", "他去了北京。", "Goodbye."),
            splitIntoSentences("Hello world. 他去了北京。Goodbye.")
        )
    }

    /**
     * 已知限制：UAX#29 没有缩写词典，`Dr.` 后面仍然断开。
     * 不做后处理 —— 把两句误并成一句会让一次分析里塞两个主句，比多切一刀更糟。
     */
    @Test
    fun `abbreviations still split because there is no abbreviation dictionary`() {
        assertEquals(
            listOf(
                "Dr.",
                "Smith went to Washington.",
                "He arrived at 3 p.m. sharp.",
                "Then he left."
            ),
            splitIntoSentences("Dr. Smith went to Washington. He arrived at 3 p.m. sharp. Then he left.")
        )
    }

    @Test
    fun `blank text has no sentences`() {
        assertTrue(splitIntoSentences("   \n\t ").isEmpty())
        assertTrue(splitIntoSentences("").isEmpty())
    }
}
