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

/**
 * 提示词变更时递增；S7 的缓存键会带上它，旧缓存自动 miss。
 *
 * v2：语法提示词从「JSON schema」换成「Markdown 讲解」，v1 的 JSON 缓存必须失效
 *（否则语法标签会把一段 JSON 当 Markdown 渲染给用户）。
 */
const val AI_PROMPT_VERSION = 2

internal const val AI_MAX_CHUNK_CHARS = 3000

private val SENTENCE_END = charArrayOf('.', '!', '?', '。', '！', '？', '…')

internal fun translationSystemPrompt(targetLanguage: String): String = """
    You are a translation engine.
    Translate the user's text into $targetLanguage.
    Output only the translation, without explanations, quotes, notes or the original text.
    Preserve the original paragraph breaks.
""".trimIndent()

/**
 * 语法提示词：只分析**用户给的那一句**，直接用 Markdown 讲。
 *
 * 不再要求 JSON schema：JSON 要模型先做一层结构化、再由客户端解析回来，模型一漏字段
 * 就整段退化成原始文本；Markdown 由模型直接写给人看，客户端只需要渲染。
 */
internal fun grammarSystemPrompt(targetLanguage: String): String = """
    You are an English grammar tutor.
    Analyze the grammar of the sentence the user provides.
    Explain:
    - the main clause structure and how subordinate clauses and phrases fit into it;
    - complex grammar points that are easy to miss;
    - important phrases/collocations and what they mean in this sentence.
    Write in $targetLanguage.
    Use short Markdown headings and bullet points.
    Be concrete and concise; do not translate the whole sentence unless it helps.
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
