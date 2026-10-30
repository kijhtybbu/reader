/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptsTest {

    @Test
    fun shortTextStaysInOneChunk() {
        assertEquals(listOf("Hello world."), chunkAiText("Hello world."))
    }

    @Test
    fun chunksNeverExceedTheLimitAndJoinBackToTheOriginal() {
        val text = buildString {
            repeat(600) { append("Sentence number ").append(it).append(". ") }
        }

        val chunks = chunkAiText(text, maxChars = 3000)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3000 })
        assertEquals(text, chunks.joinToString(separator = ""))
    }

    @Test
    fun splitsAfterSentencePunctuationWhenPossible() {
        val text = "A".repeat(2998) + ". " + "B".repeat(20)

        val chunks = chunkAiText(text, maxChars = 3000)

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].endsWith(". "))
        assertTrue(chunks[1].startsWith("B"))
        assertEquals(text, chunks.joinToString(separator = ""))
    }

    @Test
    fun splitsOnCjkSentencePunctuation() {
        val text = "中".repeat(2999) + "。" + "文".repeat(20)

        val chunks = chunkAiText(text, maxChars = 3000)

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].endsWith("。"))
        assertEquals(text, chunks.joinToString(separator = ""))
    }

    @Test
    fun hardSplitsTextWithoutWhitespace() {
        val text = "x".repeat(25)

        val chunks = chunkAiText(text, maxChars = 10)

        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), chunks)
    }

    @Test
    fun translationPromptNamesTheTargetLanguageAndForbidsExtraText() {
        val prompt = translationSystemPrompt("English (en)")

        assertTrue(prompt.contains("English (en)"))
        assertTrue(prompt.contains("Output only the translation"))
        assertTrue(prompt.contains("paragraph breaks"))
    }

    @Test
    fun grammarPromptContainsTheJsonSchema() {
        val prompt = grammarSystemPrompt("Simplified Chinese (zh-CN)")

        assertTrue(prompt.contains("JSON only"))
        assertTrue(prompt.contains("Simplified Chinese (zh-CN)"))
        listOf("summary", "sentences", "original", "translation", "structure", "points", "vocabulary", "word", "pos", "meaning")
            .forEach { assertTrue("prompt is missing $it", prompt.contains(it)) }
    }
}
