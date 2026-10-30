/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2026 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * fork 新增：语法分析 JSON 的宽容解析（第二阶段 S8，设计 §6.4/§8）。
 *
 * 语法请求要求 `response_format=json_object`，但模型仍然可能：
 *  - 把 JSON 包在 ``` 围栏里（提示词说了不要，实测仍会发生）；
 *  - 在 JSON 前后加一句「Here is the analysis:」；
 *  - 少字段、多字段、把 `points` 写成字符串而不是数组。
 *
 * 因此解析策略是「尽量取出对象、字段全部可空、认不出的条目跳过」，而不是严格反序列化：
 * 解析失败时 UI 会退化成展示原始文本（[parseGrammarAnalysis] 返回 null），
 * 不能因为一个多余逗号就把整次分析藏起来。
 *
 * 纯 JVM：只用 kotlinx.serialization 的 JsonElement API，不碰 `android.*`。
 */

package ua.acclorite.book_story.presentation.reader.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class GrammarAnalysis(
    val summary: String?,
    val sentences: List<GrammarSentence>,
    val vocabulary: List<GrammarVocabulary>
) {
    /** 三个字段都空 = 模型返回了一个空对象；UI 只提示「没有可展示的语法内容」，不回显 `{}`。 */
    val isEmpty: Boolean
        get() = summary == null && sentences.isEmpty() && vocabulary.isEmpty()
}

/** @param points 语法点；模型偶尔返回字符串而不是数组，两种都接受。 */
data class GrammarSentence(
    val original: String?,
    val translation: String?,
    val structure: String?,
    val points: List<String>
)

data class GrammarVocabulary(
    val word: String?,
    val pos: String?,
    val meaning: String?
)

/**
 * 宽容解析。
 *
 * @return null 表示「这段文本里没有可解析的 JSON 对象」（不是 JSON、或顶层是数组/字符串）。
 *   解析成功但字段全空时返回 [GrammarAnalysis.isEmpty] 为 true 的对象。
 */
fun parseGrammarAnalysis(raw: String): GrammarAnalysis? {
    val json = raw.extractJsonObject() ?: return null
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
        ?: return null

    return GrammarAnalysis(
        summary = root.string("summary"),
        sentences = root.elements("sentences").mapNotNull { it.objectOrNull()?.toSentence() },
        vocabulary = root.elements("vocabulary").mapNotNull { it.objectOrNull()?.toVocabulary() }
    )
}

/**
 * 取第一个 `{` 到最后一个 `}` 之间的内容：围栏、前后说明文字、代码块语言标记都在外面，
 * 不需要逐个剥。围栏里出现 `{`/`}` 之外的字符不影响结果。
 */
private fun String.extractJsonObject(): String? {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return substring(start, end + 1)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.elements(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList().orEmpty()

private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.toSentence(): GrammarSentence? {
    val sentence = GrammarSentence(
        original = string("original"),
        translation = string("translation"),
        structure = string("structure"),
        points = points()
    )
    // 模型偶尔返回空对象占位：全空的条目没有展示价值，跳过。
    if (sentence.original == null && sentence.translation == null &&
        sentence.structure == null && sentence.points.isEmpty()
    ) {
        return null
    }
    return sentence
}

private fun JsonObject.points(): List<String> = when (val value = this["points"]) {
    is JsonArray -> value.mapNotNull { it.textOrNull() }
    is JsonPrimitive -> listOfNotNull(value.textOrNull())
    else -> emptyList()
}

private fun JsonObject.toVocabulary(): GrammarVocabulary? {
    val vocabulary = GrammarVocabulary(
        word = string("word"),
        pos = string("pos"),
        meaning = string("meaning")
    )
    if (vocabulary.word == null && vocabulary.pos == null && vocabulary.meaning == null) return null
    return vocabulary
}

private fun JsonElement.textOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
