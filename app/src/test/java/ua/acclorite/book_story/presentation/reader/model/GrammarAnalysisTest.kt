/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：语法 JSON 宽容解析（第二阶段 S8，设计 §6.4/§8）。
 *
 * 钉住「解析失败不能让结果消失」：能救的都救回来，救不回来的返回 null 让 UI 退化成原始文本。
 */

package ua.acclorite.book_story.presentation.reader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarAnalysisTest {

    @Test
    fun `a complete payload is parsed into all sections`() {
        val analysis = parseGrammarAnalysis(
            """
            {
              "summary": "两个并列的对照句。",
              "sentences": [
                {
                  "original": "It was the best of times.",
                  "translation": "那是最好的时代。",
                  "structure": "主系表结构",
                  "points": ["并列对照", "过去时"]
                }
              ],
              "vocabulary": [
                {"word": "worst", "pos": "adj.", "meaning": "最坏的"}
              ]
            }
            """.trimIndent()
        )

        requireNotNull(analysis)
        assertEquals("两个并列的对照句。", analysis.summary)
        assertEquals(1, analysis.sentences.size)
        val sentence = analysis.sentences.single()
        assertEquals("It was the best of times.", sentence.original)
        assertEquals("那是最好的时代。", sentence.translation)
        assertEquals("主系表结构", sentence.structure)
        assertEquals(listOf("并列对照", "过去时"), sentence.points)
        assertEquals(
            GrammarVocabulary(word = "worst", pos = "adj.", meaning = "最坏的"),
            analysis.vocabulary.single()
        )
    }

    @Test
    fun `markdown fences and surrounding prose are ignored`() {
        val analysis = parseGrammarAnalysis(
            """
            Here is the analysis:
            ```json
            {"summary": "只有总览"}
            ```
            Hope this helps.
            """.trimIndent()
        )

        assertEquals("只有总览", analysis?.summary)
    }

    @Test
    fun `missing fields stay null and empty lists`() {
        val analysis = requireNotNull(parseGrammarAnalysis("""{"sentences": []}"""))

        assertNull(analysis.summary)
        assertTrue(analysis.sentences.isEmpty())
        assertTrue(analysis.vocabulary.isEmpty())
    }

    @Test
    fun `extra fields are ignored`() {
        val analysis = parseGrammarAnalysis(
            """
            {
              "summary": "总览",
              "confidence": 0.92,
              "sentences": [
                {"original": "A.", "translation": "甲。", "notes": "ignored"}
              ],
              "unknown": {"nested": true}
            }
            """.trimIndent()
        )

        assertEquals("总览", analysis?.summary)
        assertEquals("甲。", analysis?.sentences?.single()?.translation)
    }

    @Test
    fun `points may be a single string`() {
        val analysis = parseGrammarAnalysis(
            """{"sentences": [{"original": "A.", "points": "只有一个语法点"}]}"""
        )

        assertEquals(listOf("只有一个语法点"), analysis?.sentences?.single()?.points)
    }

    @Test
    fun `empty sentence and vocabulary entries are skipped`() {
        val analysis = requireNotNull(
            parseGrammarAnalysis(
                """
                {
                  "sentences": [{}, "not an object", {"original": "A."}],
                  "vocabulary": [{}, {"meaning": "只在语境里的意思"}]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, analysis.sentences.size)
        assertEquals("A.", analysis.sentences.single().original)
        assertEquals(1, analysis.vocabulary.size)
        assertNull(analysis.vocabulary.single().word)
        assertEquals("只在语境里的意思", analysis.vocabulary.single().meaning)
    }

    @Test
    fun `an empty object parses but is marked empty`() {
        val analysis = requireNotNull(parseGrammarAnalysis("{}"))

        assertTrue(analysis.isEmpty)
    }

    @Test
    fun `invalid json returns null so the UI can fall back to the raw text`() {
        assertNull(parseGrammarAnalysis("not json at all"))
        assertNull(parseGrammarAnalysis("""{"summary": "truncated" """))
        assertNull(parseGrammarAnalysis("""["array", "at", "top"]"""))
        assertNull(parseGrammarAnalysis(""))
    }

    @Test
    fun `non-string primitives are ignored instead of crashing`() {
        val analysis = requireNotNull(
            parseGrammarAnalysis(
                """
                {
                  "summary": 42,
                  "sentences": [{"original": 7, "translation": true}]
                }
                """.trimIndent()
            )
        )

        // 数字/布尔会被读成字面量字符串，不崩；关键是整份结果仍然可展示。
        assertEquals("42", analysis.summary)
        assertEquals("7", analysis.sentences.single().original)
    }
}
