/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 语法提示词只要求 Markdown 讲解，不再要求 JSON schema：JSON 一漏字段就整段退化成原始文本，
     * 而 Markdown 是模型直接写给人看的东西，客户端只负责渲染（设计 §6.4/§8.2）。
     */
    @Test
    fun grammarPromptAsksForMarkdownAndNamesTheTargetLanguage() {
        val prompt = grammarSystemPrompt("Simplified Chinese (zh-CN)")

        assertTrue(prompt.contains("Simplified Chinese (zh-CN)"))
        assertTrue(prompt.contains("grammar"))
        assertTrue(prompt.contains("Markdown"))
        assertTrue(prompt.contains("main clause"))
        assertTrue(prompt.contains("collocations"))
        assertFalse("语法提示词不该再要求 JSON：$prompt", prompt.contains("JSON"))
        assertFalse(prompt.contains("schema"))
    }

    /**
     * 提示词版本进缓存键：JSON 时代的 v1 缓存必须整批失效，否则语法标签会把一段 JSON
     * 当 Markdown 渲染给用户。
     */
    @Test
    fun promptVersionIsTwo() {
        assertEquals(2, AI_PROMPT_VERSION)
    }
}
