/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：AI 提示词与长文本切块（第二阶段 S6，设计 §6.4）。
 *
 * 切块必须保持「拼回去等于原文」：模型是按块顺序输出的，任何丢字/重复都会直接进译文。
 * 优先在句末标点 + 空白处切；单句超过上限时退回最近的空白，仍没有就硬切。
 */

package ua.acclorite.book_story.data.ai

/** 提示词变更时递增；S7 的缓存键会带上它，旧缓存自动 miss。 */
const val AI_PROMPT_VERSION = 1

internal const val AI_MAX_CHUNK_CHARS = 3000

private val SENTENCE_END = charArrayOf('.', '!', '?', '。', '！', '？', '…')

internal fun translationSystemPrompt(targetLanguage: String): String = """
    You are a translation engine.
    Translate the user's text into $targetLanguage.
    Output only the translation, without explanations, quotes, notes or the original text.
    Preserve the original paragraph breaks.
""".trimIndent()

internal fun grammarSystemPrompt(targetLanguage: String): String = """
    You are a grammar analyzer. Analyze the user's text and return JSON only, without Markdown fences.
    Translate each sentence into $targetLanguage.
    The JSON must match this schema:
    {
      "summary": "short overall summary",
      "sentences": [
        {
          "original": "original sentence",
          "translation": "translation",
          "structure": "grammatical structure",
          "points": ["grammar point"]
        }
      ],
      "vocabulary": [
        {"word": "word", "pos": "part of speech", "meaning": "meaning in this context"}
      ]
    }
""".trimIndent()

internal fun chunkAiText(text: String, maxChars: Int = AI_MAX_CHUNK_CHARS): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    if (text.length <= maxChars) return listOf(text)

    val chunks = ArrayList<String>(text.length / maxChars + 1)
    var start = 0
    while (start < text.length) {
        val hardEnd = minOf(start + maxChars, text.length)
        if (hardEnd == text.length) {
            chunks += text.substring(start)
            break
        }

        var split = sentenceBoundary(text, start, hardEnd)
        if (split <= start) {
            split = whitespaceBoundary(text, start, hardEnd)
        }
        chunks += text.substring(start, split)
        start = split
    }
    return chunks
}

/** 返回 `[start, end]` 内最后一个句末标点之后的位置（并吃掉其后的空白），没有则 -1。 */
private fun sentenceBoundary(text: String, start: Int, end: Int): Int {
    var i = end - 1
    while (i > start) {
        if (text[i] in SENTENCE_END) {
            var split = i + 1
            while (split < end && text[split].isWhitespace()) split++
            return split
        }
        i--
    }
    return -1
}

/** 返回 `[start, end]` 内最后一个空白之后的位置，没有则 [end]（硬切）。 */
private fun whitespaceBoundary(text: String, start: Int, end: Int): Int {
    var i = end
    while (i > start && !text[i - 1].isWhitespace()) i--
    return if (i > start) i else end
}
